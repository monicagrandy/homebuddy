import json
import secrets
from contextlib import asynccontextmanager
from datetime import datetime, time

from fastapi import Depends, FastAPI, File, Form, HTTPException, UploadFile
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
from backend.models import (
    Asset,
    Case,
    ConversationMessage,
    Document,
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
    AssetCreate,
    AssetOut,
    CaseCreate,
    CaseUpdate,
    CaseOut,
    ConversationMessageOut,
    DocumentIndexResponse,
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
from backend.services.ingestion_service import IngestionError, IngestionService
from backend.services.query_service import QueryService
from rag.query_engine import QueryEngineError

logger = get_logger(__name__)

_TEMPORARY_LLM_OVERLOAD_MESSAGE = (
    "HomeBuddy is temporarily busy handling AI requests. Please try again in a few seconds."
)


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
        with engine.begin() as connection:
            connection.execute(text("CREATE EXTENSION IF NOT EXISTS vector"))
    Base.metadata.create_all(engine)
    _ensure_document_schema()
    if settings.warm_runtime_on_startup:
        logger.info("Runtime warmup enabled; preloading critical components.")
        warm_runtime_components()
    yield


app = FastAPI(lifespan=lifespan)


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

def _append_conversation_message(
    session: Session,
    *,
    household_id: int,
    session_id: str,
    role: str,
    content: str,
) -> None:
    session.add(
        ConversationMessage(
            household_id=household_id,
            session_id=session_id,
            role=role,
            content=content,
        )
    )
    session.commit()

def _clear_conversation_history(
    session: Session,
    *,
    household_id: int,
    session_id: str,
) -> None:
    session.execute(
        delete(ConversationMessage).where(
            ConversationMessage.household_id == household_id,
            ConversationMessage.session_id == session_id,
        )
    )
    session.commit()

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


@app.get("/conversations/{session_id}/messages", response_model=list[ConversationMessageOut])
def list_conversation_messages(
    session_id: str,
    household_id: int,
    session: Session = Depends(get_session),
    identity: AuthenticatedIdentity = Depends(get_current_identity),
):
    user = _get_or_create_user(session, identity)
    _require_household_membership(session, user.id, household_id)
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
    _clear_conversation_history(
        session,
        household_id=household_id,
        session_id=session_id,
    )


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


@app.post("/documents/index", response_model=DocumentIndexResponse)
async def index_document(
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

        result = service.index(
            entry_id=entry_id,
            household_id=household_id,
            session_id=session_id,
            url=source_url,
            file_bytes=source_file_bytes,
            doc_type=doc_type,
        )

        if document is None:
            document = Document(
                entry_id=entry_id,
                household_id=household_id,
                asset_id=None,
                doc_type=doc_type,
                source_name=display_name or (file.filename if file else entry_id),
                source_url=source_url,
                file_bytes=source_file_bytes,
                storage_key = None
            )
            session.add(document)
        else:
            document.source_name = display_name or document.source_name or (file.filename if file else entry_id)
            document.source_url = source_url
            document.file_bytes = source_file_bytes
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
    except IngestionError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc


@app.post("/query", response_model=QueryResponse)
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
                session_id=payload.session_id,
                entry_id=payload.entry_id,
                household_id=payload.household_id,
                asset_id=payload.asset_id,
                household_zip_code=payload.household_zip_code,
                messages=prior_messages,
            )
        # Persist the PII-anonymized query, not the raw one, and skip blocked turns entirely.
        if not result.get("input_blocked"):
            _append_conversation_message(
                session,
                household_id=payload.household_id,
                session_id=payload.session_id,
                role="user",
                content=result.get("sanitized_query") or payload.question,
            )
            _append_conversation_message(
                session,
                household_id=payload.household_id,
                session_id=payload.session_id,
                role="assistant",
                content=result["answer"],
            )
            session.commit()
        return QueryResponse(**result)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except SafetyBlockError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except QueryEngineError as exc:
        raise HTTPException(status_code=503, detail=str(exc)) from exc
    except (RateLimitError, APIConnectionError, APITimeoutError) as exc:
        _raise_query_http_error(exc)


@app.post("/query/stream")
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

    def event_stream():
        final_result = None
        sanitized_query = None
        with SessionLocal() as persist_session:
            try:
                with tracing_context(tags=trace_tags, metadata=trace_metadata):
                    for event in service.stream_query(
                        user_query=payload.question,
                        session_id=payload.session_id,
                        entry_id=payload.entry_id,
                        household_id=payload.household_id,
                        asset_id=payload.asset_id,
                        household_zip_code=payload.household_zip_code,
                        messages=prior_messages,
                    ):
                        if not sanitized_query and event.get("type") == "user_accepted" and event.get("sanitized_query") is not None:
                            sanitized_query = event["sanitized_query"]
                            _append_conversation_message(
                                persist_session,
                                household_id=payload.household_id,
                                session_id=payload.session_id,
                                role="user",
                                content=sanitized_query,
                            )
                        if event.get("type") == "final":
                            final_result = event["result"]
                            _append_conversation_message(
                                persist_session,
                                household_id=payload.household_id,
                                session_id=payload.session_id,
                                role="assistant",
                                content=final_result["answer"],
                            )
                        yield f"data: {json.dumps(jsonable_encoder(event))}\n\n"
            except ValueError as exc:
                message = _streaming_query_error_message(exc)
                _append_conversation_message(
                    persist_session,
                    household_id=payload.household_id,
                    session_id=payload.session_id,
                    role="assistant",
                    content=message,
                )
                yield f"data: {json.dumps({'type': 'error', 'message': message})}\n\n"
            except SafetyBlockError as exc:
                message = _streaming_query_error_message(exc)
                _append_conversation_message(
                    persist_session,
                    household_id=payload.household_id,
                    session_id=payload.session_id,
                    role="assistant",
                    content=message,
                )
                yield f"data: {json.dumps({'type': 'error', 'message': message})}\n\n"
            except QueryEngineError as exc:
                message = _streaming_query_error_message(exc)
                _append_conversation_message(
                    persist_session,
                    household_id=payload.household_id,
                    session_id=payload.session_id,
                    role="assistant",
                    content=message,
                )
                yield f"data: {json.dumps({'type': 'error', 'message': message})}\n\n"
            except (RateLimitError, APIConnectionError, APITimeoutError) as exc:
                message = _streaming_query_error_message(exc)
                _append_conversation_message(
                    persist_session,
                    household_id=payload.household_id,
                    session_id=payload.session_id,
                    role="assistant",
                    content=message,
                )
                logger.warning("Streaming query hit temporary AI provider error: %s", exc)
                yield f"data: {json.dumps({'type': 'error', 'message': message})}\n\n"
            except Exception as exc:
                _append_conversation_message(
                    persist_session,
                    household_id=payload.household_id,
                    session_id=payload.session_id,
                    role="assistant",
                    content=str(exc),
                )
                logger.exception("Streaming query failed")
                yield f"data: {json.dumps({'type': 'error', 'message': str(exc)})}\n\n"
               

    return StreamingResponse(event_stream(), media_type="text/event-stream")
