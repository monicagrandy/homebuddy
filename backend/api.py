import asyncio
import json
import queue
import secrets
import threading
from contextlib import asynccontextmanager
from datetime import datetime, time, timedelta, timezone
from urllib.parse import urlparse

import uuid

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, Request, Response, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.encoders import jsonable_encoder
from fastapi.responses import StreamingResponse
from langchain_core.messages import AIMessage, HumanMessage
from langsmith.run_helpers import tracing_context
from openai import APIConnectionError, APITimeoutError, RateLimitError
from sqlalchemy import delete, func, select, text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from backend.auth import (
    AuthenticatedIdentity,
    ensure_cognito_beta_access,
    exchange_auth_code_for_tokens,
    get_current_identity,
    refresh_cognito_tokens,
    validate_cognito_id_token,
)
from backend.config import get_logger, settings
from backend.db import Base, SessionLocal, engine, get_session
from backend.dependencies import (
    get_ingestion_service,
    get_query_service,
    get_vector_store,
    warm_runtime_components,
)
from backend.guardrails.guardrails import SafetyBlockError
from backend.ingestion.loader import DocumentLoadError
from backend.ingestion.pipeline import IngestionCancelled
from backend.middleware import TrustedHostGuard
from backend.migrations import upgrade_database
from backend.rate_limit import rate_limited
from backend.models import (
    Asset,
    Case,
    Conversation,
    ConversationMessage,
    Document,
    IngestionJob,
    Household,
    HouseholdInvitation,
    HouseholdMember,
    MaintenanceTask,
    User,
)
from backend.schemas import (
    AuthBootstrapRequest,
    AuthCodeExchangeRequest,
    AuthCodeExchangeResponse,
    AuthRefreshRequest,
    AssetCreate,
    AssetOut,
    CaseCreate,
    CaseUpdate,
    CaseOut,
    ConversationMessageOut,
    ConversationOut,
    DocumentIndexResponse,
    DocumentJobOut,
    DocumentOut,
    HouseholdCreate,
    HouseholdInvitationCreate,
    HouseholdInvitationOut,
    HouseholdOut,
    QueryRequest,
    QueryResponse,
    TaskCreate,
    TaskUpdate,
    TaskOut,
    UserOut,
)
from backend.services import conversations as conversation_store
from backend.services.document_jobs import DocumentJobRunner, DocumentJobWorker, utc_now
from backend.services.maintenance import MaintenanceWorker
from backend.services.documents import allocate_entry_id, find_duplicate, sha256_hex
from backend.services.ingestion_service import IngestionError, IngestionService
from backend.services.query_service import QueryService
from rag.query_engine import QueryEngineError

logger = get_logger(__name__)

_UNEXPECTED_QUERY_ERROR_MESSAGE = "Something went wrong while answering. Please try again."
_TEMPORARY_LLM_OVERLOAD_MESSAGE = (
    "HomeBuddy is temporarily busy handling AI requests. Please try again in a few seconds."
)
_DISCONNECT_POLL_SECONDS = 0.5
# Comment lines keep phones, proxies and load balancers from closing a quiet stream.
_SSE_PING_SECONDS = 15.0
# Bumped when a streamed event changes shape in a way clients must handle.
_STREAM_EVENT_VERSION = 1
_STREAM_DONE = object()
_document_job_worker: DocumentJobWorker | None = None
_maintenance_worker: MaintenanceWorker | None = None
_CONVERSATION_BUSY_MESSAGE = "Still answering your last question in this conversation. Try again in a moment."


def _maintenance_tasks(job_runner: DocumentJobRunner) -> dict:
    tasks = {"finished_document_jobs": job_runner.purge_finished}
    if settings.conversation_retention_days > 0:
        retention = timedelta(days=settings.conversation_retention_days)

        def purge_conversations() -> int:
            with SessionLocal() as session:
                return conversation_store.purge_inactive(session, older_than=retention)

        tasks["inactive_conversations"] = purge_conversations
    return tasks


def _owned_conversation(session: Session, *, household_id: int, session_id: str, user: User) -> Conversation | None:
    try:
        return conversation_store.get_owned(session, household_id=household_id, session_id=session_id, user_id=user.id)
    except conversation_store.ConversationNotFound:
        raise HTTPException(status_code=404, detail="Conversation not found.") from None


def _start_conversation_query(session: Session, *, household_id: int, session_id: str, user: User) -> Conversation:
    """The thread for this question, locked so another device can't ask in it at the same time."""
    try:
        conversation = conversation_store.ensure(
            session, household_id=household_id, session_id=session_id, user_id=user.id
        )
    except conversation_store.ConversationNotFound:
        raise HTTPException(status_code=404, detail="Conversation not found.") from None
    if not conversation_store.try_start_query(session, conversation.id):
        raise HTTPException(status_code=409, detail=_CONVERSATION_BUSY_MESSAGE)
    return conversation


def _finish_conversation_query(session: Session, conversation_id: int) -> None:
    try:
        session.rollback()
        conversation_store.finish_query(session, conversation_id)
    except Exception:
        # The lock goes stale on its own after a few minutes.
        logger.exception("Could not release conversation %s", conversation_id)
# nginx's "client closed request" code; the caller is gone, so it is only seen in logs.
_CLIENT_CLOSED_REQUEST = 499


def _raise_query_http_error(exc: Exception) -> None:
    if isinstance(exc, RateLimitError):
        raise HTTPException(
            status_code=503,
            detail=_TEMPORARY_LLM_OVERLOAD_MESSAGE,
            headers={"Retry-After": "1"},
        ) from exc
    if isinstance(exc, (APIConnectionError, APITimeoutError)):
        raise HTTPException(
            status_code=503,
            detail="HomeBuddy could not reach the AI provider. Please try again shortly.",
        ) from exc
    raise exc


def _streaming_query_error_message(exc: Exception) -> str:
    if isinstance(exc, RateLimitError):
        return _TEMPORARY_LLM_OVERLOAD_MESSAGE
    if isinstance(exc, (APIConnectionError, APITimeoutError)):
        return "HomeBuddy could not reach the AI provider. Please try again shortly."
    return str(exc)

def _ensure_document_schema():
    if not settings.database_url.startswith("sqlite"):
        return

    with engine.begin() as connection:
        document_columns = {
            row[1] for row in connection.execute(text("PRAGMA table_info(documents)"))
        }
        if "entry_id" not in document_columns:
            connection.execute(text("ALTER TABLE documents ADD COLUMN entry_id VARCHAR"))
            connection.execute(
                text(
                    "UPDATE documents SET entry_id = COALESCE(source_name, CAST(id AS TEXT)) WHERE entry_id IS NULL"
                )
            )
        if "storage_key" not in document_columns:
            connection.execute(text("ALTER TABLE documents ADD COLUMN storage_key VARCHAR"))


@asynccontextmanager
async def lifespan(app: FastAPI):
    tracing_enabled = settings.langsmith_tracing == True
    logger.info(f"Langsmith tracing enabled: {tracing_enabled}")
    missing = [
        name
        for name, value in {
            "COGNITO_ISSUER": settings.cognito_issuer,
            "COGNITO_JWKS_URL": settings.cognito_jwks_url,
            "COGNITO_APP_CLIENT_ID": settings.cognito_app_client_id,
        }.items()
        if not value
    ]
    if missing:
        raise RuntimeError(
            f"Cognito configuration is incomplete. Missing: {', '.join(missing)}"
        )
    if settings.database_url.startswith("postgresql"):
        # Postgres schema is owned by Alembic (alembic/versions).
        upgrade_database(engine)
    else:
        # SQLite is only used for tests and quick local runs.
        Base.metadata.create_all(engine)
        _ensure_document_schema()
    if settings.warm_runtime_on_startup:
        logger.info("Runtime warmup enabled; preloading critical components.")
        warm_runtime_components()
    global _document_job_worker, _maintenance_worker
    job_runner = DocumentJobRunner(session_factory=SessionLocal, ingestion_service_factory=get_ingestion_service)
    if settings.document_jobs_enabled:
        _document_job_worker = DocumentJobWorker(job_runner)
        _document_job_worker.start()
    if settings.maintenance_enabled:
        _maintenance_worker = MaintenanceWorker(_maintenance_tasks(job_runner))
        _maintenance_worker.start()
    try:
        yield
    finally:
        for worker in (_document_job_worker, _maintenance_worker):
            if worker is not None:
                worker.stop()
        _document_job_worker = None
        _maintenance_worker = None


def api_docs_options(*, production: bool) -> dict:
    # The interactive docs and schema are for development; the public API doesn't advertise itself.
    if production:
        return {"docs_url": None, "redoc_url": None, "openapi_url": None}
    return {}


app = FastAPI(lifespan=lifespan, **api_docs_options(production=settings.is_production))
app.add_middleware(TrustedHostGuard)


def _langsmith_trace_tags(*, identity: AuthenticatedIdentity, streamed: bool) -> list[str]:
    tags = [
        "homebuddy",
        "auth:cognito",
        "beta:gated" if settings.cognito_allowed_groups else "beta:open",
        "query:stream" if streamed else "query:sync",
    ]
    tags.extend(f"group:{group}" for group in sorted(identity.groups))
    return tags


def _langsmith_trace_metadata(
    *,
    payload: QueryRequest,
    identity: AuthenticatedIdentity,
    user: User,
    streamed: bool,
) -> dict:
    return {
        "user_id": user.id,
        "cognito_subject": identity.subject,
        "cognito_email": user.email,
        "cognito_groups": sorted(identity.groups),
        "beta_gate_enabled": bool(settings.cognito_allowed_groups),
        "query_mode": "stream" if streamed else "sync",
        "household_id": payload.household_id,
        "asset_id": payload.asset_id,
        "entry_id": payload.entry_id,
        "session_id": payload.session_id,
    }


def _as_datetime(value):
    if value is None:
        return None
    return datetime.combine(value, time.min)

def _require_asset_in_household(
    session: Session,
    asset_id: int,
    household_id: int,
) -> Asset:
    asset = session.get(Asset, asset_id)
    if asset is None:
        raise HTTPException(status_code=404, detail="Asset not found.")
    if asset.household_id != household_id:
        # 404 (not 403) so responses don't confirm the existence of other households' assets.
        raise HTTPException(status_code=404, detail="Asset not found.")
    return asset

def _get_or_create_user(
    session: Session,
    identity: AuthenticatedIdentity,
    *,
    create_if_missing: bool = False,
) -> User:
    ensure_cognito_beta_access(identity)
    user = session.scalar(select(User).where(User.cognito_sub == identity.subject))
    if user is None:
        if not create_if_missing:
            raise HTTPException(
                status_code=401,
                detail="User profile has not been initialized. Sign in again to complete bootstrap.",
            )
        if not identity.email:
            raise HTTPException(
                status_code=401,
                detail="User profile has not been initialized. Sign in again to complete bootstrap.",
            )
        user = User(
            cognito_sub=identity.subject,
            email=identity.email,
            display_name=identity.display_name,
        )
        try:
            session.add(user)
            session.commit()
            session.refresh(user)
            return user
        except IntegrityError:
            session.rollback()
            user = session.scalar(select(User).where(User.cognito_sub == identity.subject))
            if user is None:
                raise
            return user

    updated = False
    if identity.email and user.email != identity.email:
        user.email = identity.email
        updated = True
    if identity.display_name and user.display_name != identity.display_name:
        user.display_name = identity.display_name
        updated = True
    if updated:
        session.commit()
        session.refresh(user)
    return user


def _require_owner_membership(session: Session, user_id: int, household_id: int) -> HouseholdMember:
    membership = _require_household_membership(session, user_id, household_id)

    if membership.role != "owner":
        raise HTTPException(status_code=403, detail="Only household owners can perform that action.")
    return membership


def _require_household_membership(session: Session, user_id: int, household_id: int) -> HouseholdMember:
    membership = session.scalar(
        select(HouseholdMember).where(
            HouseholdMember.user_id == user_id,
            HouseholdMember.household_id == household_id,
        )
    )
    if membership is None:
        raise HTTPException(status_code=403, detail="Household membership not found.")
    return membership

def _list_household_entities_for_member(
    entity,
    household_id: int,
    session: Session,
    user: User,
):
    _require_household_membership(session, user.id, household_id)
    return session.scalars(
        select(entity).where(entity.household_id == household_id).order_by(entity.id)
    ).all()


def _resolve_sources(session: Session, *, household_id: int, raw_sources: list[dict]) -> list[dict]:
    """Turn retrieved chunk sources into one CitationOut per (document, page), in retrieval order."""
    seen: set[tuple[str, int | None]] = set()
    ordered: list[dict] = []
    for raw in raw_sources:
        key = (raw.get("entry_id"), raw.get("page"))
        if not key[0] or key in seen:
            continue
        seen.add(key)
        ordered.append(raw)
    if not ordered:
        return []

    documents = {
        document.entry_id: document
        for document in session.scalars(
            select(Document).where(
                Document.household_id == household_id,
                Document.entry_id.in_({raw["entry_id"] for raw in ordered}),
            )
        )
    }
    citations = []
    for raw in ordered:
        document = documents.get(raw["entry_id"])
        if document is None:
            # Deleted since it was indexed (for example from another device).
            continue
        citations.append(
            {
                "entry_id": document.entry_id,
                "display_name": document.source_name,
                "page": raw.get("page"),
                "doc_type": raw.get("doc_type") or document.doc_type,
                "url": document.source_url,
            }
        )
    return citations


def _load_conversation_history(
    session: Session,
    *,
    household_id: int,
    session_id: str,
) -> list[ConversationMessage]:
    history = session.scalars(
        select(ConversationMessage)
        .where(
            ConversationMessage.household_id == household_id,
            ConversationMessage.session_id == session_id,
        )
        .order_by(ConversationMessage.id.desc()).limit(4)
    ).all()
    history.reverse()
    return history


def _load_full_conversation_history(
    session: Session,
    *,
    household_id: int,
    session_id: str,
) -> list[ConversationMessage]:
    return session.scalars(
        select(ConversationMessage)
        .where(
            ConversationMessage.household_id == household_id,
            ConversationMessage.session_id == session_id,
        )
        .order_by(ConversationMessage.id.asc())
    ).all()


def _to_langchain_messages(history: list[ConversationMessage]):
    messages = []
    for message in history:
        if message.role == "user":
            messages.append(HumanMessage(content=message.content))
        elif message.role == "assistant":
            messages.append(AIMessage(content=message.content))
    return messages

@app.get("/health")
def read_root():
    return {"message": "Welcome to FastAPI"}


@app.get("/auth/me", response_model=UserOut)
def get_me(
    identity: AuthenticatedIdentity = Depends(get_current_identity),
    session: Session = Depends(get_session),
):
    return _get_or_create_user(session, identity, create_if_missing=True)

@app.post("/auth/bootstrap", response_model=UserOut)
def auth_bootstrap(
    payload: AuthBootstrapRequest,
    session: Session = Depends(get_session),
):
    identity = validate_cognito_id_token(payload.id_token, access_token=payload.access_token)
    user = _get_or_create_user(session, identity, create_if_missing=True)
    return user

@app.post("/auth/exchange", response_model=AuthCodeExchangeResponse)
def exchange_auth_code(payload: AuthCodeExchangeRequest):
    return exchange_auth_code_for_tokens(payload.code)


@app.post("/auth/refresh", response_model=AuthCodeExchangeResponse)
def refresh_auth_tokens(payload: AuthRefreshRequest):
    return refresh_cognito_tokens(payload.refresh_token)


@app.get("/conversations/{session_id}/messages", response_model=list[ConversationMessageOut])
def list_conversation_messages(
    session_id: str,
    household_id: int,
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity),
):
    user = _get_or_create_user(session, identity)
    _require_household_membership(session, user.id, household_id)
    if _owned_conversation(session, household_id=household_id, session_id=session_id, user=user) is None:
        return []
    return _load_full_conversation_history(
        session,
        household_id=household_id,
        session_id=session_id,
    )


@app.delete("/conversations/{session_id}/messages", status_code=204)
def clear_conversation_messages(
    session_id: str,
    household_id: int,
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity),
):
    user = _get_or_create_user(session, identity)
    _require_household_membership(session, user.id, household_id)
    conversation = _owned_conversation(session, household_id=household_id, session_id=session_id, user=user)
    if conversation is not None:
        conversation_store.delete_conversation(session, conversation)


def _conversation_out(conversation: Conversation) -> ConversationOut:
    return ConversationOut(
        id=conversation.session_id,
        household_id=conversation.household_id,
        title=conversation.title or "New conversation",
        created_at=conversation_store.as_utc(conversation.created_at),
        updated_at=conversation_store.as_utc(conversation.updated_at),
    )


@app.get("/conversations", response_model=list[ConversationOut])
def list_conversations(
    household_id: int,
    updated_since: datetime | None = None,
    limit: int = Query(50, ge=1, le=200),
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity),
):
    """The signed-in user's threads, most recent first. Pass updated_since to fetch only changes."""
    user = _get_or_create_user(session, identity)
    _require_household_membership(session, user.id, household_id)
    if updated_since is not None and updated_since.tzinfo is None:
        updated_since = updated_since.replace(tzinfo=timezone.utc)
    return [
        _conversation_out(conversation)
        for conversation in conversation_store.list_for_user(
            session, household_id=household_id, user_id=user.id, updated_since=updated_since, limit=limit
        )
    ]


@app.delete("/conversations/{session_id}", status_code=204)
def delete_conversation(
    session_id: str,
    household_id: int,
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity),
):
    user = _get_or_create_user(session, identity)
    _require_household_membership(session, user.id, household_id)
    conversation = _owned_conversation(session, household_id=household_id, session_id=session_id, user=user)
    if conversation is None:
        raise HTTPException(status_code=404, detail="Conversation not found.")
    conversation_store.delete_conversation(session, conversation)


@app.get("/households", response_model=list[HouseholdOut])
def list_households(
    identity: AuthenticatedIdentity = Depends(get_current_identity),
    session: Session = Depends(get_session),
):
    user = _get_or_create_user(session, identity)
    memberships = session.scalars(
        select(HouseholdMember).where(HouseholdMember.user_id == user.id)
    ).all()
    households = []
    for membership in memberships:
        household = session.get(Household, membership.household_id)
        if household is None:
            continue
        households.append(
            HouseholdOut(
                id=household.id,
                name=household.name,
                zip_code=household.zip_code,
                home_type=household.home_type,
                role=membership.role,
            )
        )
    return households


@app.post("/households", response_model=HouseholdOut, status_code=201)
def create_household(
    payload: HouseholdCreate,
    identity: AuthenticatedIdentity = Depends(get_current_identity),
    session: Session = Depends(get_session),
):
    user = _get_or_create_user(session, identity)
    household = Household(
        name=payload.name,
        zip_code=payload.zip_code,
        home_type=payload.home_type,
    )
    session.add(household)
    session.commit()
    session.refresh(household)
    membership = HouseholdMember(household_id=household.id, user_id=user.id, role="owner")
    session.add(membership)
    session.commit()
    return HouseholdOut(
        id=household.id,
        name=household.name,
        zip_code=household.zip_code,
        home_type=household.home_type,
        role="owner",
    )

@app.get("/assets", response_model=list[AssetOut])
def list_assets(
    household_id: int,
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity),
):
    user = _get_or_create_user(session, identity)
    return _list_household_entities_for_member(Asset, household_id, session, user)

@app.post("/assets", response_model=AssetOut, status_code=201)
def create_asset(
    payload: AssetCreate, 
    session: Session = Depends(get_session), 
    identity: AuthenticatedIdentity = Depends(get_current_identity)
):
    user = _get_or_create_user(session, identity)
    _require_household_membership(session, user.id, payload.household_id)
    household = session.get(Household, payload.household_id)
    if household is None:
        raise HTTPException(status_code=404, detail="Household not found.")

    asset = Asset(
        household_id=payload.household_id,
        name=payload.name,
        brand=payload.brand,
        model_number=payload.model_number,
        room=payload.room,
        install_date=_as_datetime(payload.install_date),
        warranty_end_date=_as_datetime(payload.warranty_end_date),
    )
    session.add(asset)
    session.commit()
    session.refresh(asset)
    return asset


@app.get("/tasks", response_model=list[TaskOut])
def list_tasks(
    household_id: int,
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity)
):
    user = _get_or_create_user(session, identity)
    return _list_household_entities_for_member(MaintenanceTask, household_id, session, user)


@app.post("/tasks", response_model=TaskOut, status_code=201)
def create_task(
    payload: TaskCreate, 
    session: Session = Depends(get_session), 
    identity: AuthenticatedIdentity = Depends(get_current_identity)
):
    user = _get_or_create_user(session, identity)
    _require_household_membership(session, user.id, payload.household_id)

    if payload.asset_id is not None:
        _require_asset_in_household(session, payload.asset_id, payload.household_id)

    task = MaintenanceTask(
        household_id=payload.household_id,
        asset_id=payload.asset_id,
        title=payload.title,
        due_date=_as_datetime(payload.due_date),
        status="pending",
        notes=payload.notes,
    )
    session.add(task)
    session.commit()
    session.refresh(task)
    return task

@app.get("/cases", response_model=list[CaseOut])
def list_cases(
    household_id: int,
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity)
):
    user = _get_or_create_user(session, identity)
    return _list_household_entities_for_member(Case, household_id, session, user)

@app.post("/cases", response_model=CaseOut, status_code=201)
def create_case(payload: CaseCreate, session: Session = Depends(get_session), identity: AuthenticatedIdentity = Depends(get_current_identity)):
    user = _get_or_create_user(session, identity)
    _require_household_membership(session, user.id, payload.household_id)
   
    if payload.asset_id is not None:
        _require_asset_in_household(session, payload.asset_id, payload.household_id)

    case = Case(
        household_id=payload.household_id,
        asset_id=payload.asset_id,
        title=payload.title,
        summary=payload.summary,
        severity=payload.severity,
        status="open",
        contractor_trade=payload.contractor_trade or "general",
    )
    session.add(case)
    session.commit()
    session.refresh(case)
    return case


@app.put("/cases/{case_id}", response_model=CaseOut)
def update_case(
    case_id: int,
    payload: CaseUpdate,
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity),
):
    user = _get_or_create_user(session, identity)
    case = session.get(Case, case_id)
    if case is None:
        raise HTTPException(status_code=404, detail="Case not found.")

    _require_household_membership(session, user.id, case.household_id)

    case.title = payload.title
    case.summary = payload.summary
    case.severity = payload.severity
    case.contractor_trade = payload.contractor_trade or "general"
    case.status = payload.status
    session.commit()
    session.refresh(case)
    return case


@app.delete("/cases/{case_id}", status_code=204)
def delete_case(
    case_id: int,
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity),
):
    user = _get_or_create_user(session, identity)
    case = session.get(Case, case_id)
    if case is None:
        raise HTTPException(status_code=404, detail="Case not found.")

    _require_household_membership(session, user.id, case.household_id)

    session.delete(case)
    session.commit()

@app.put("/tasks/{task_id}", response_model=TaskOut)
def update_task(
    task_id: int,
    payload: TaskUpdate,
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity),
):
    user = _get_or_create_user(session, identity)
    task = session.get(MaintenanceTask, task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="Task not found.")

    _require_household_membership(session, user.id, task.household_id)

    task.title = payload.title
    task.notes = payload.notes
    task.due_date = _as_datetime(payload.due_date)
    session.commit()
    session.refresh(task)
    return task


@app.delete("/tasks/{task_id}", status_code=204)
def delete_task(
    task_id: int,
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity),
):
    user = _get_or_create_user(session, identity)
    task = session.get(MaintenanceTask, task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="Task not found.")

    _require_household_membership(session, user.id, task.household_id)

    session.delete(task)
    session.commit()

@app.delete("/assets/{asset_id}", status_code=204)
def delete_asset(
    asset_id: int,
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity),
):
    user = _get_or_create_user(session, identity)
    asset = session.get(Asset, asset_id)
    if asset is None:
        raise HTTPException(status_code=404, detail="Asset not found.")

    _require_household_membership(session, user.id, asset.household_id)

    session.delete(asset)
    session.commit()

@app.get("/documents", response_model=list[DocumentOut])
def get_documents(
    household_id: int, 
    session: Session = Depends(get_session), 
    identity: AuthenticatedIdentity = Depends(get_current_identity)
):
    user = _get_or_create_user(session, identity)
    documents = _list_household_entities_for_member(Document, household_id, session, user)
    return [
        DocumentOut(
            id=document.id,
            entry_id=document.entry_id,
            display_name=document.source_name,
            download_url=document.source_url,
            doc_type=document.doc_type,
            uploaded_at=document.uploaded_at,
            status=document.status,
        )
        for document in documents
    ]

def _delete_document_entry(
    session: Session,
    *,
    household_id: int,
    entry_id: str,
) -> None:
    document = session.scalar(
        select(Document).where(
            Document.entry_id == entry_id,
            Document.household_id == household_id,
        )
    )
    if document is None:
        raise HTTPException(status_code=404, detail="Document not found.")

    get_vector_store().delete_entry(
        household_id=household_id,
        entry_id=entry_id,
    )
    session.delete(document)
    session.commit()


def _delete_all_document_entries(
    session: Session,
    *,
    household_id: int,
) -> int:
    documents = session.scalars(
        select(Document).where(Document.household_id == household_id)
    ).all()
    if not documents:
        return 0

    deleted_count = 0
    for document in documents:
        get_vector_store().delete_entry(
            household_id=household_id,
            entry_id=document.entry_id,
        )
        session.delete(document)
        deleted_count += 1

    session.commit()
    return deleted_count


@app.delete("/documents/{entry_id}", status_code=204)
def delete_document(
    entry_id: str,
    household_id: int,
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity),
):
    user = _get_or_create_user(session, identity)
    _require_household_membership(session, user.id, household_id)

    _delete_document_entry(
        session,
        household_id=household_id,
        entry_id=entry_id,
    )


@app.delete("/documents", status_code=204)
def delete_all_documents(
    household_id: int,
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity),
):
    user = _get_or_create_user(session, identity)
    _require_household_membership(session, user.id, household_id)
    _delete_all_document_entries(
        session,
        household_id=household_id,
    )


async def _cancel_on_disconnect(request: Request, cancelled: threading.Event) -> None:
    while not cancelled.is_set():
        if await request.is_disconnected():
            cancelled.set()
            return
        await asyncio.sleep(_DISCONNECT_POLL_SECONDS)


@app.post(
    "/documents/index",
    response_model=DocumentIndexResponse,
    dependencies=[Depends(rate_limited("documents"))],
)
async def index_document(
    request: Request,
    household_id: int = Form(...),
    entry_id: str = Form(...),
    session_id: str = Form(...),
    url: str | None = Form(None),
    display_name: str | None = Form(None),
    doc_type: str = Form(...),
    file: UploadFile | None = File(None),
    service: IngestionService = Depends(get_ingestion_service),
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity)
):
    user = _get_or_create_user(session, identity)
    _require_household_membership(session, user.id, household_id)
    file_bytes = await file.read() if file else None

    try:
        document = session.scalar(
            select(Document).where(
                Document.entry_id == entry_id,
                Document.household_id == household_id,
            )
        )
        source_url = url
        source_file_bytes = file_bytes

        if source_url is None and source_file_bytes is None and document is not None:
            source_url = document.source_url
            source_file_bytes = document.file_bytes

        if source_url is None and source_file_bytes is None:
            raise HTTPException(status_code=404, detail="No stored content found for this document.")

        # Indexing blocks for as long as PII redaction and embedding take. Run it
        # off the event loop so other requests keep being served, and stop it if
        # the caller hangs up (page refresh, closed tab, or client timeout).
        cancelled = threading.Event()
        disconnect_watcher = asyncio.create_task(_cancel_on_disconnect(request, cancelled))
        try:
            result = await run_in_threadpool(
                service.index,
                entry_id=entry_id,
                household_id=household_id,
                session_id=session_id,
                url=source_url,
                file_bytes=source_file_bytes,
                doc_type=doc_type,
                should_cancel=cancelled.is_set,
            )
        finally:
            disconnect_watcher.cancel()

        if cancelled.is_set() and document is None:
            # The caller left after chunks were written. Drop them so a new
            # upload is all-or-nothing. A re-index has already replaced the
            # previous chunks, so it is saved instead of left half-done.
            await run_in_threadpool(service.discard, household_id=household_id, entry_id=entry_id)
            raise IngestionCancelled("Indexing was cancelled before the document was saved.")

        if document is None:
            document = Document(
                entry_id=entry_id,
                household_id=household_id,
                asset_id=None,
                doc_type=doc_type,
                source_name=display_name or (file.filename if file else entry_id),
                source_url=source_url,
                file_bytes=source_file_bytes,
                storage_key = None,
                content_sha256=sha256_hex(source_file_bytes) if source_file_bytes else None,
                uploaded_by_user_id=user.id,
            )
            session.add(document)
        else:
            document.source_name = display_name or document.source_name or (file.filename if file else entry_id)
            document.source_url = source_url
            document.file_bytes = source_file_bytes
            document.content_sha256 = sha256_hex(source_file_bytes) if source_file_bytes else None
            document.storage_key = None
            document.doc_type = doc_type
            if document.household_id is None:
                document.household_id = household_id

        session.commit()
        return DocumentIndexResponse(**result)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except DocumentLoadError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except IngestionCancelled as exc:
        logger.info("Discarded document entry_id=%s after the client disconnected", entry_id)
        raise HTTPException(status_code=_CLIENT_CLOSED_REQUEST, detail=str(exc)) from exc
    except IngestionError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc


def _job_out(job: IngestionJob) -> DocumentJobOut:
    return DocumentJobOut(
        job_id=job.id,
        entry_id=job.entry_id,
        status=job.status,
        error_code=job.error_code,
        error=job.error,
        chunks_indexed=job.chunks_indexed,
        attempts=job.attempts,
    )


def _upload_error(status_code: int, code: str, message: str) -> HTTPException:
    return HTTPException(status_code=status_code, detail={"code": code, "message": message})


def _read_upload(file: UploadFile) -> bytes:
    data = file.file.read(settings.max_upload_bytes + 1)
    if len(data) > settings.max_upload_bytes:
        raise _upload_error(
            413, "too_large", f"The PDF is too large. Maximum supported size is {settings.max_upload_bytes // (1024 * 1024)} MB."
        )
    if not data:
        raise _upload_error(422, "empty", "The uploaded file is empty.")
    if b"%PDF-" not in data[:1024]:
        raise _upload_error(422, "not_pdf", "The uploaded file is not a PDF.")
    return data


def _validated_url(url: str) -> str:
    url = url.strip()
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc or len(url) > 2048:
        raise _upload_error(422, "invalid_url", "Enter a web address that starts with http:// or https://.")
    return url


@app.post(
    "/documents/jobs",
    response_model=DocumentJobOut,
    status_code=202,
    dependencies=[Depends(rate_limited("documents"))],
)
def create_document_job(
    response: Response,
    household_id: int = Form(...),
    display_name: str = Form(..., min_length=1, max_length=200),
    doc_type: str = Form(..., min_length=1, max_length=50),
    url: str | None = Form(None),
    file: UploadFile | None = File(None),
    idempotency_key: str | None = Header(None, alias="Idempotency-Key", max_length=100),
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity),
):
    """Store a PDF (or URL) and index it in the background. Poll GET /documents/jobs/{job_id}."""
    user = _get_or_create_user(session, identity)
    _require_household_membership(session, user.id, household_id)

    if idempotency_key:
        existing_job = session.scalar(
            select(IngestionJob).where(IngestionJob.user_id == user.id, IngestionJob.idempotency_key == idempotency_key)
        )
        if existing_job is not None:
            response.status_code = 200
            return _job_out(existing_job)

    if (file is None) == (url is None):
        raise _upload_error(400, "invalid", "Provide either a PDF file or a URL.")

    file_bytes = _read_upload(file) if file is not None else None
    source_url = _validated_url(url) if url is not None else None
    content_sha256 = sha256_hex(file_bytes) if file_bytes else None

    duplicate = find_duplicate(session, household_id=household_id, content_sha256=content_sha256, source_url=source_url)
    if duplicate is not None:
        response.status_code = 200
        return DocumentJobOut(entry_id=duplicate.entry_id, status="duplicate", duplicate_of=duplicate.entry_id)

    for attempt in range(2):
        entry_id = allocate_entry_id(session, household_id=household_id, display_name=display_name.strip())
        session.add(
            Document(
                entry_id=entry_id,
                household_id=household_id,
                doc_type=doc_type,
                source_name=display_name.strip(),
                source_url=source_url,
                file_bytes=file_bytes,
                content_sha256=content_sha256,
                uploaded_by_user_id=user.id,
                status="indexing",
            )
        )
        job = IngestionJob(
            id=str(uuid.uuid4()),
            household_id=household_id,
            user_id=user.id,
            entry_id=entry_id,
            created_document=True,
            status="queued",
            attempts=0,
            idempotency_key=idempotency_key,
            run_after=utc_now(),
        )
        session.add(job)
        try:
            session.commit()
            break
        except IntegrityError:
            # Another request (often a retry from the same phone) won the race.
            session.rollback()
            if idempotency_key:
                existing_job = session.scalar(
                    select(IngestionJob).where(
                        IngestionJob.user_id == user.id, IngestionJob.idempotency_key == idempotency_key
                    )
                )
                if existing_job is not None:
                    response.status_code = 200
                    return _job_out(existing_job)
            if attempt == 1:
                raise

    if _document_job_worker is not None:
        _document_job_worker.notify()
    return _job_out(job)


@app.get("/documents/jobs/{job_id}", response_model=DocumentJobOut)
def get_document_job(
    job_id: str,
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity),
):
    user = _get_or_create_user(session, identity)
    job = session.get(IngestionJob, job_id)
    if job is None or session.scalar(
        select(HouseholdMember).where(HouseholdMember.user_id == user.id, HouseholdMember.household_id == job.household_id)
    ) is None:
        raise HTTPException(status_code=404, detail="Job not found.")
    return _job_out(job)


@app.post("/query", response_model=QueryResponse, dependencies=[Depends(rate_limited("query"))])
def query(
    payload: QueryRequest,
    service: QueryService = Depends(get_query_service),
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity)
):
    user = _get_or_create_user(session, identity)
    _require_household_membership(session, user.id, payload.household_id)

    if payload.asset_id is not None:
        _require_asset_in_household(session, payload.asset_id, payload.household_id)    

    conversation = _start_conversation_query(
        session, household_id=payload.household_id, session_id=payload.session_id, user=user
    )
    try:
        prior_messages = _to_langchain_messages(
            _load_conversation_history(
                session,
                household_id=payload.household_id,
                session_id=payload.session_id,
            )
        )
        with tracing_context(
            tags=_langsmith_trace_tags(identity=identity, streamed=False),
            metadata=_langsmith_trace_metadata(
                payload=payload,
                identity=identity,
                user=user,
                streamed=False,
            ),
        ):
            result = service.run_query(
                user_query=payload.question,
                user_id=user.id,
                session_id=payload.session_id,
                entry_id=payload.entry_id,
                household_id=payload.household_id,
                asset_id=payload.asset_id,
                household_zip_code=payload.household_zip_code,
                messages=prior_messages,
            )
        result = {
            **result,
            "sources": _resolve_sources(
                session,
                household_id=payload.household_id,
                raw_sources=result.get("sources", []),
            ),
        }
        # Persist the PII-anonymized query, not the raw one, and skip blocked turns entirely.
        if not result.get("input_blocked"):
            conversation_store.append_message(
                session,
                conversation,
                role="user",
                content=result.get("sanitized_query") or payload.question,
            )
            conversation_store.append_message(session, conversation, role="assistant", content=result["answer"])
        return QueryResponse(**result)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except SafetyBlockError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except QueryEngineError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except (RateLimitError, APIConnectionError, APITimeoutError) as exc:
        _raise_query_http_error(exc)
    finally:
        _finish_conversation_query(session, conversation.id)


@app.post("/query/stream", dependencies=[Depends(rate_limited("query"))])
def query_stream(
    payload: QueryRequest,
    service: QueryService = Depends(get_query_service),
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity)
):
    user = _get_or_create_user(session, identity)
    _require_household_membership(session, user.id, payload.household_id)

    if payload.asset_id is not None:
        _require_asset_in_household(session, payload.asset_id, payload.household_id)

    conversation = _start_conversation_query(
        session, household_id=payload.household_id, session_id=payload.session_id, user=user
    )
    conversation_pk = conversation.id
    prior_messages = _to_langchain_messages(
        _load_conversation_history(
            session,
            household_id=payload.household_id,
            session_id=payload.session_id,
        )
    )
    trace_tags = _langsmith_trace_tags(identity=identity, streamed=True)
    trace_metadata = _langsmith_trace_metadata(
        payload=payload,
        identity=identity,
        user=user,
        streamed=True,
    )

    conversation_id = payload.session_id

    def produce(emit) -> None:
        """Run the graph to completion and save the turn, whether or not the client is still listening."""
        sanitized_query = None
        with SessionLocal() as persist_session, tracing_context(tags=trace_tags, metadata=trace_metadata):
            thread = persist_session.get(Conversation, conversation_pk)

            def save(role: str, content: str) -> None:
                # Skipped if the thread was deleted meanwhile (for example on another device).
                if thread is not None:
                    conversation_store.append_message(persist_session, thread, role=role, content=content)

            try:
                for event in service.stream_query(
                    user_query=payload.question,
                    user_id=user.id,
                    session_id=payload.session_id,
                    entry_id=payload.entry_id,
                    household_id=payload.household_id,
                    asset_id=payload.asset_id,
                    household_zip_code=payload.household_zip_code,
                    messages=prior_messages,
                ):
                    if event.get("type") == "user_accepted":
                        event = {**event, "conversation_id": conversation_id}
                        if not sanitized_query and event.get("sanitized_query") is not None:
                            sanitized_query = event["sanitized_query"]
                            save("user", sanitized_query)
                    if event.get("type") == "final":
                        result = {
                            **event["result"],
                            "sources": _resolve_sources(
                                persist_session,
                                household_id=payload.household_id,
                                raw_sources=event["result"].get("sources", []),
                            ),
                        }
                        event = {**event, "result": result}
                        save("assistant", result["answer"])
                    emit(event)
            except (ValueError, SafetyBlockError, QueryEngineError) as exc:
                message = _streaming_query_error_message(exc)
                save("assistant", message)
                emit({"type": "error", "message": message})
            except (RateLimitError, APIConnectionError, APITimeoutError) as exc:
                logger.warning("Streaming query hit temporary AI provider error: %s", exc)
                message = _streaming_query_error_message(exc)
                save("assistant", message)
                emit({"type": "error", "message": message})
            except Exception:
                # Unexpected errors can carry internal details, so the client only gets a generic message.
                logger.exception("Streaming query failed")
                save("assistant", _UNEXPECTED_QUERY_ERROR_MESSAGE)
                emit({"type": "error", "message": _UNEXPECTED_QUERY_ERROR_MESSAGE})
            finally:
                _finish_conversation_query(persist_session, conversation_pk)

    events: queue.Queue = queue.Queue()

    def run_producer() -> None:
        try:
            produce(events.put)
        except Exception:
            logger.exception("Streaming query could not save its result")
            events.put({"type": "error", "message": _UNEXPECTED_QUERY_ERROR_MESSAGE})
        finally:
            events.put(_STREAM_DONE)

    # The graph runs in its own thread so a dropped connection (a phone backgrounding the app)
    # never cuts the answer short; the client can fetch it from the conversation afterwards.
    threading.Thread(target=run_producer, name="query-stream", daemon=True).start()

    def event_stream():
        while True:
            try:
                item = events.get(timeout=_SSE_PING_SECONDS)
            except queue.Empty:
                yield ": ping\n\n"
                continue
            if item is _STREAM_DONE:
                return
            yield f"data: {json.dumps(jsonable_encoder({'v': _STREAM_EVENT_VERSION, **item}))}\n\n"

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
