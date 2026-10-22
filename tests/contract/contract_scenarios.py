"""Record the API's real responses as contract fixtures for the native apps.

Each scenario drives the actual FastAPI app (stubbing only the AI and indexing
services), then normalizes ids and timestamps so the files are stable.
tests/contract/test_api_contract.py checks the committed files still match;
scripts/export_contract.py rewrites them.
"""

import json
import re
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from backend import api
from backend.auth import AuthenticatedIdentity
from backend.db import Base
from backend.rate_limit import limiter
from backend.services.document_jobs import DocumentJobRunner

CONTRACTS_DIR = Path(__file__).resolve().parents[2] / "contracts"
FIXTURES_DIR = CONTRACTS_DIR / "fixtures"
OPENAPI_PATH = CONTRACTS_DIR / "openapi.json"

PDF = b"%PDF-1.4\n% Bosch dishwasher manual\n"

_TIMESTAMP = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})?")
_UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

ANSWER = (
    "Twist the filter counterclockwise, lift it out and rinse it under warm water "
    "(Bosch Dishwasher Manual, page 12)."
)


class ContractQueryService:
    def run_query(self, **kwargs) -> dict:
        return {**_final_result(kwargs["user_query"])}

    def stream_query(self, **kwargs):
        yield {"type": "user_accepted", "sanitized_query": kwargs["user_query"], "input_blocked": False}
        yield {"type": "status", "message": "🧭 Routing complete: document_qa_agent"}
        yield {"type": "status", "message": "📚 Document agent checked the saved docs and finished its pass."}
        yield {"type": "status", "message": "✨ Final response is ready."}
        yield {"type": "final", "result": _final_result(kwargs["user_query"])}


class GasLeakQueryService(ContractQueryService):
    def stream_query(self, **kwargs):
        yield {"type": "user_accepted", "sanitized_query": kwargs["user_query"], "input_blocked": False}
        yield {
            "type": "final",
            "result": {
                **_final_result(kwargs["user_query"]),
                "answer": "Leave your home now and call 911 or your gas company from outside.",
                "route": ["safety_risk_agent"],
                "urgency_level": "critical",
                "should_escalate": True,
                "sources": [],
            },
        }


class FailingQueryService:
    def stream_query(self, **kwargs):
        yield {"type": "user_accepted", "sanitized_query": kwargs["user_query"], "input_blocked": False}
        raise RuntimeError("internal details that must not reach clients")


def _final_result(question: str) -> dict:
    return {
        "answer": ANSWER,
        "query": question,
        "sanitized_query": question,
        "input_blocked": False,
        "route": ["document_qa_agent"],
        "route_confidence": 0.92,
        "route_explanation": "Manual troubleshooting request",
        "urgency_level": "low",
        "should_escalate": False,
        "case_draft": None,
        "task_draft": None,
        "contractor_suggestions": [],
        "retrieval_context": [],
        "sources": [
            {"entry_id": "bosch-dishwasher-manual", "source": "bosch-dishwasher-manual", "page": 12, "doc_type": "manual"}
        ],
    }


class StubIngestion:
    def index(self, **kwargs) -> dict:
        return {"entry_id": kwargs["entry_id"], "chunks_indexed": 12}

    def discard(self, **_kwargs) -> None:
        return None


@contextmanager
def contract_client(tmp_dir: Path) -> Iterator[TestClient]:
    """A TestClient on a fresh SQLite database, signed in as one user (like tests/api/conftest.py)."""
    engine = create_engine(f"sqlite:///{tmp_dir / 'contract.db'}", connect_args={"check_same_thread": False})
    session_local = sessionmaker(bind=engine, autoflush=False, autocommit=False)
    Base.metadata.create_all(engine)
    saved = (api.engine, api.SessionLocal, api.settings.database_url, api.settings.rate_limit_queries_per_minute)
    api.engine, api.SessionLocal = engine, session_local
    api.settings.database_url = f"sqlite:///{tmp_dir / 'contract.db'}"

    def override_get_session():
        with session_local() as session:
            yield session

    api.app.dependency_overrides.clear()
    api.app.dependency_overrides[api.get_session] = override_get_session
    api.app.dependency_overrides[api.get_current_identity] = lambda: AuthenticatedIdentity(
        subject="contract-user-sub", email="pat@example.com", display_name="Pat Doe"
    )
    api.app.dependency_overrides[api.get_query_service] = ContractQueryService
    api.app.dependency_overrides[api.get_ingestion_service] = StubIngestion
    limiter.reset()
    try:
        with TestClient(api.app) as client:
            client.session_local = session_local
            yield client
    finally:
        api.app.dependency_overrides.clear()
        api.engine, api.SessionLocal, api.settings.database_url, api.settings.rate_limit_queries_per_minute = saved
        limiter.reset()
        engine.dispose()


def _normalize(text: str) -> str:
    text = _TIMESTAMP.sub("2026-01-01T00:00:00Z", text)
    seen: dict[str, str] = {}
    for match in _UUID.findall(text):
        seen.setdefault(match, f"00000000-0000-0000-0000-{len(seen) + 1:012d}")
    for original, replacement in seen.items():
        text = text.replace(original, replacement)
    return text


def _exchange(response, *, headers: tuple[str, ...] = ()) -> str:
    request = response.request
    record = {
        "request": {"method": request.method, "path": request.url.path, "query": request.url.query.decode() or None},
        "status": response.status_code,
        "body": response.json() if response.content else None,
    }
    kept = {name: response.headers[name] for name in headers if name in response.headers}
    if kept:
        record["headers"] = kept
    return json.dumps(record, indent=2, ensure_ascii=False) + "\n"


def generate(tmp_dir: Path) -> dict[str, str]:
    """Fixture file name -> contents."""
    files: dict[str, str] = {}
    with contract_client(tmp_dir) as client:
        files["client_config.json"] = _exchange(client.get("/client-config"))
        files["auth_me.json"] = _exchange(client.get("/auth/me"))
        files["households_empty.json"] = _exchange(client.get("/households"))
        created = client.post("/households", json={"name": "Elm Street", "zip_code": "80202", "home_type": "single_family"})
        files["household_create.json"] = _exchange(created)
        household_id = created.json()["id"]
        files["households.json"] = _exchange(client.get("/households"))

        form = {"household_id": str(household_id), "display_name": "Bosch Dishwasher Manual", "doc_type": "manual"}
        queued = client.post(
            "/documents/jobs",
            data=form,
            files={"file": ("manual.pdf", PDF, "application/pdf")},
            headers={"Idempotency-Key": "contract-upload-1"},
        )
        files["document_job_queued.json"] = _exchange(queued)
        files["documents_indexing.json"] = _exchange(client.get("/documents", params={"household_id": household_id}))
        runner = DocumentJobRunner(session_factory=client.session_local, ingestion_service_factory=StubIngestion)
        while runner.run_next() is not None:
            pass
        files["document_job_succeeded.json"] = _exchange(client.get(f"/documents/jobs/{queued.json()['job_id']}"))
        files["documents_ready.json"] = _exchange(client.get("/documents", params={"household_id": household_id}))
        files["document_job_duplicate.json"] = _exchange(
            client.post("/documents/jobs", data=form, files={"file": ("copy.pdf", PDF, "application/pdf")})
        )
        files["document_upload_not_pdf.json"] = _exchange(
            client.post("/documents/jobs", data=form, files={"file": ("page.html", b"<html></html>", "text/html")})
        )

        question = {"question": "How do I clean the dishwasher filter?", "session_id": "thread-1", "household_id": household_id}
        files["query.json"] = _exchange(client.post("/query", json=question))
        files["query_stream_answer.sse"] = _normalize(client.post("/query/stream", json={**question, "session_id": "thread-2"}).text)

        api.app.dependency_overrides[api.get_query_service] = GasLeakQueryService
        files["query_stream_safety.sse"] = _normalize(
            client.post("/query/stream", json={**question, "question": "I smell gas in the kitchen", "session_id": "thread-3"}).text
        )
        api.app.dependency_overrides[api.get_query_service] = FailingQueryService
        files["query_stream_error.sse"] = _normalize(
            client.post("/query/stream", json={**question, "question": "Why is it beeping?", "session_id": "thread-4"}).text
        )
        api.app.dependency_overrides[api.get_query_service] = ContractQueryService

        files["conversations.json"] = _exchange(client.get("/conversations", params={"household_id": household_id}))
        files["conversation_messages.json"] = _exchange(
            client.get("/conversations/thread-1/messages", params={"household_id": household_id})
        )

        with client.session_local() as session:
            conversation_id = session.query(api.Conversation).filter_by(session_id="thread-1").one().id
            api.conversation_store.try_start_query(session, conversation_id)
        files["error_conversation_busy.json"] = _exchange(client.post("/query", json=question))

        api.settings.rate_limit_queries_per_minute = 1
        limiter.reset()
        client.post("/query", json={**question, "session_id": "thread-5"})
        files["error_rate_limited.json"] = _exchange(
            client.post("/query", json={**question, "session_id": "thread-6"}), headers=("retry-after",)
        )

        files["error_not_found.json"] = _exchange(client.get("/documents/jobs/missing-job"))
        files["conversation_delete.json"] = _exchange(
            client.delete("/conversations/thread-2", params={"household_id": household_id})
        )
        api.app.dependency_overrides[api.get_cognito_user_deleter] = lambda: None
        files["account_delete.json"] = _exchange(client.delete("/me"))

    return {name: _normalize(content) for name, content in sorted(files.items())}


def openapi_schema() -> str:
    return json.dumps(api.app.openapi(), indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def breaking_changes(old: dict, new: dict) -> list[str]:
    """Changes that would break an app built against `old`: removed operations, removed or retyped fields."""
    problems: list[str] = []
    for path, operations in old.get("paths", {}).items():
        for method in operations:
            if method not in new.get("paths", {}).get(path, {}):
                problems.append(f"removed {method.upper()} {path}")

    old_schemas = old.get("components", {}).get("schemas", {})
    new_schemas = new.get("components", {}).get("schemas", {})
    for name, schema in old_schemas.items():
        if name not in new_schemas:
            problems.append(f"removed schema {name}")
            continue
        new_properties = new_schemas[name].get("properties", {})
        for field, definition in schema.get("properties", {}).items():
            if field not in new_properties:
                problems.append(f"removed field {name}.{field}")
            elif _type_of(definition) != _type_of(new_properties[field]):
                problems.append(f"changed type of {name}.{field}: {_type_of(definition)} -> {_type_of(new_properties[field])}")
    return problems


def _type_of(definition: dict) -> str:
    if "$ref" in definition:
        return definition["$ref"].rsplit("/", 1)[-1]
    if "anyOf" in definition:
        return " | ".join(sorted(_type_of(option) for option in definition["anyOf"]))
    if definition.get("type") == "array":
        return f"array[{_type_of(definition.get('items', {}))}]"
    return definition.get("type", "any")
