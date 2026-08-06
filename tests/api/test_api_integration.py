from collections.abc import Generator

import httpx
import pytest
from fastapi.testclient import TestClient
from openai import RateLimitError
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from backend import api
from backend.auth import AuthenticatedIdentity
from backend.db import Base


class StubIngestionService:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    def index(self, **kwargs) -> dict:
        self.calls.append(kwargs)
        return {"entry_id": kwargs["entry_id"], "chunks_indexed": 2}


class StubQueryService:
    def __init__(self, result: dict) -> None:
        self.result = result
        self.calls: list[dict] = []

    def run_query(self, **kwargs) -> dict:
        self.calls.append(kwargs)
        return self.result


class RaisingQueryService:
    def __init__(self, error: Exception) -> None:
        self.error = error

    def run_query(self, **_kwargs) -> dict:
        raise self.error


@pytest.fixture(autouse=True)
def clear_dependency_overrides() -> Generator[None, None, None]:
    api.app.dependency_overrides.clear()
    yield
    api.app.dependency_overrides.clear()


@pytest.fixture
def client(tmp_path) -> Generator[TestClient, None, None]:
    db_path = tmp_path / "api_test.db"
    engine = create_engine(
        f"sqlite:///{db_path}",
        connect_args={"check_same_thread": False},
    )
    testing_session_local = sessionmaker(bind=engine, autoflush=False, autocommit=False)
    Base.metadata.create_all(engine)

    def override_get_session():
        with testing_session_local() as session:
            yield session

    api.app.dependency_overrides[api.get_session] = override_get_session
    api.app.dependency_overrides[api.get_current_identity] = lambda: AuthenticatedIdentity(
        subject="dev-user-sub",
        email="dev@example.com",
        display_name="Dev User",
    )

    with TestClient(api.app) as test_client:
        yield test_client

    engine.dispose()


def _create_household(client: TestClient) -> dict:
    bootstrap_response = client.get("/auth/me")
    assert bootstrap_response.status_code == 200

    response = client.post(
        "/households",
        json={
            "name": "Primary Home",
            "zip_code": "80202",
            "home_type": "single_family",
        },
    )
    assert response.status_code == 201
    return response.json()


def test_auth_me_creates_user_from_authenticated_identity(client: TestClient):
    response = client.get("/auth/me")

    assert response.status_code == 200
    assert response.json() == {
        "id": 1,
        "cognito_sub": "dev-user-sub",
        "email": "dev@example.com",
        "display_name": "Dev User",
    }


def test_auth_me_rejects_user_outside_allowed_cognito_groups(client: TestClient, monkeypatch):
    monkeypatch.setattr(api.settings, "cognito_allowed_groups", ("beta_testers",))

    response = client.get("/auth/me")

    assert response.status_code == 403
    assert response.json() == {
        "detail": "This account is not enabled for the HomeBuddy beta."
    }


def test_auth_me_allows_user_in_allowed_cognito_group(client: TestClient, monkeypatch):
    monkeypatch.setattr(api.settings, "cognito_allowed_groups", ("beta_testers",))
    api.app.dependency_overrides[api.get_current_identity] = lambda: AuthenticatedIdentity(
        subject="beta-user-sub",
        email="beta@example.com",
        display_name="Beta User",
        groups=["beta_testers"],
    )

    response = client.get("/auth/me")

    assert response.status_code == 200
    assert response.json() == {
        "id": 1,
        "cognito_sub": "beta-user-sub",
        "email": "beta@example.com",
        "display_name": "Beta User",
    }


def test_create_and_list_households_for_authenticated_user(client: TestClient):
    created = _create_household(client)

    assert created == {
        "id": 1,
        "name": "Primary Home",
        "zip_code": "80202",
        "home_type": "single_family",
        "role": "owner",
    }

    list_response = client.get("/households")

    assert list_response.status_code == 200
    assert list_response.json() == [created]


def test_document_upload_is_saved_and_can_be_reindexed_without_reupload(client: TestClient):
    household = _create_household(client)
    service = StubIngestionService()
    api.app.dependency_overrides[api.get_ingestion_service] = lambda: service

    upload_response = client.post(
        "/documents/index",
        data={
            "household_id": str(household["id"]),
            "entry_id": "dishwasher-manual",
            "session_id": "session-1",
            "display_name": "Dishwasher Manual",
            "doc_type": "manual",
        },
        files={"file": ("manual.pdf", b"%PDF-1.4 fake", "application/pdf")},
    )

    assert upload_response.status_code == 200
    assert upload_response.json() == {
        "entry_id": "dishwasher-manual",
        "chunks_indexed": 2,
    }
    assert service.calls[0]["file_bytes"] == b"%PDF-1.4 fake"
    assert service.calls[0]["url"] is None

    reindex_response = client.post(
        "/documents/index",
        data={
            "household_id": str(household["id"]),
            "entry_id": "dishwasher-manual",
            "session_id": "session-2",
            "doc_type": "manual",
        },
    )

    assert reindex_response.status_code == 200
    assert reindex_response.json() == {
        "entry_id": "dishwasher-manual",
        "chunks_indexed": 2,
    }
    assert service.calls[1]["file_bytes"] == b"%PDF-1.4 fake"
    assert service.calls[1]["session_id"] == "session-2"

    documents_response = client.get(
        "/documents",
        params={"household_id": household["id"]},
    )

    assert documents_response.status_code == 200
    assert documents_response.json() == [
        {
            "id": 1,
            "entry_id": "dishwasher-manual",
            "display_name": "Dishwasher Manual",
            "download_url": None,
            "doc_type": "manual",
            "uploaded_at": documents_response.json()[0]["uploaded_at"],
        }
    ]


def test_query_persists_sanitized_messages_to_conversation_history(client: TestClient):
    household = _create_household(client)
    service = StubQueryService(
        {
            "answer": "Clean the dishwasher filter and run a hot cycle.",
            "sanitized_query": "How do I clean the dishwasher?",
            "input_blocked": False,
            "route": ["troubleshooting_agent"],
            "route_confidence": 0.91,
            "route_explanation": "Manual troubleshooting request",
            "retrieval_context": [],
            "contractor_suggestions": [],
        }
    )
    api.app.dependency_overrides[api.get_query_service] = lambda: service

    response = client.post(
        "/query",
        json={
            "question": "How do I clean the dishwasher filter?",
            "session_id": "session-42",
            "household_id": household["id"],
        },
    )

    assert response.status_code == 200
    assert response.json()["answer"] == "Clean the dishwasher filter and run a hot cycle."
    assert service.calls == [
        {
            "user_query": "How do I clean the dishwasher filter?",
            "session_id": "session-42",
            "entry_id": None,
            "household_id": household["id"],
            "asset_id": None,
            "household_zip_code": None,
            "messages": [],
        }
    ]

    messages_response = client.get(
        "/conversations/session-42/messages",
        params={"household_id": household["id"]},
    )

    assert messages_response.status_code == 200
    assert messages_response.json() == [
        {
            "id": 1,
            "household_id": household["id"],
            "session_id": "session-42",
            "role": "user",
            "content": "How do I clean the dishwasher?",
            "created_at": messages_response.json()[0]["created_at"],
        },
        {
            "id": 2,
            "household_id": household["id"],
            "session_id": "session-42",
            "role": "assistant",
            "content": "Clean the dishwasher filter and run a hot cycle.",
            "created_at": messages_response.json()[1]["created_at"],
        },
    ]


def test_query_does_not_persist_blocked_turns(client: TestClient):
    household = _create_household(client)
    service = StubQueryService(
        {
            "answer": "Blocked",
            "sanitized_query": "redacted",
            "input_blocked": True,
            "retrieval_context": [],
            "contractor_suggestions": [],
        }
    )
    api.app.dependency_overrides[api.get_query_service] = lambda: service

    response = client.post(
        "/query",
        json={
            "question": "Unsafe request",
            "session_id": "session-blocked",
            "household_id": household["id"],
        },
    )

    assert response.status_code == 200

    messages_response = client.get(
        "/conversations/session-blocked/messages",
        params={"household_id": household["id"]},
    )

    assert messages_response.status_code == 200
    assert messages_response.json() == []


def test_query_returns_503_for_upstream_rate_limit(client: TestClient):
    household = _create_household(client)
    request = httpx.Request("POST", "https://api.openai.com/v1/chat/completions")
    response = httpx.Response(429, request=request)
    error = RateLimitError(
        "Rate limit reached for gpt-4o.",
        response=response,
        body={"error": {"message": "Rate limit reached for gpt-4o."}},
    )
    api.app.dependency_overrides[api.get_query_service] = lambda: RaisingQueryService(error)

    result = client.post(
        "/query",
        json={
            "question": "What can you help me with as a homeowner?",
            "session_id": "session-rate-limit",
            "household_id": household["id"],
        },
    )

    assert result.status_code == 503
    assert result.headers["retry-after"] == "1"
    assert result.json() == {
        "detail": "HomeBuddy is temporarily busy handling AI requests. Please try again in a few seconds."
    }
