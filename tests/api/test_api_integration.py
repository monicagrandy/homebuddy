import threading

import httpx
import pytest
from fastapi.testclient import TestClient
from openai import RateLimitError

from backend import api
from backend.auth import AuthenticatedIdentity
from backend.ingestion.pipeline import IngestionCancelled
from cognito_fakes import TEST_MOBILE_CLIENT_ID, TEST_WEB_CLIENT_ID


class StubIngestionService:
    def __init__(self) -> None:
        self.calls: list[dict] = []
        self.discarded: list[dict] = []

    def index(self, **kwargs) -> dict:
        self.calls.append(kwargs)
        return {"entry_id": kwargs["entry_id"], "chunks_indexed": 2}

    def discard(self, **kwargs) -> None:
        self.discarded.append(kwargs)


class StubQueryService:
    def __init__(self, result: dict, stream_events: list[dict] | None = None) -> None:
        self.result = result
        self.calls: list[dict] = []
        self.stream_calls: list[dict] = []
        self.stream_events = stream_events or []

    def run_query(self, **kwargs) -> dict:
        self.calls.append(kwargs)
        return self.result

    def stream_query(self, **kwargs):
        self.stream_calls.append(kwargs)
        for event in self.stream_events:
            yield event


class RaisingQueryService:
    def __init__(self, error: Exception) -> None:
        self.error = error

    def run_query(self, **_kwargs) -> dict:
        raise self.error


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


def test_auth_bootstrap_creates_user_from_mobile_client_tokens(client: TestClient, cognito_tokens):
    access_token = cognito_tokens.access_token(sub="android-sub", client_id=TEST_MOBILE_CLIENT_ID)
    id_token = cognito_tokens.id_token(
        sub="android-sub",
        email="android@example.com",
        client_id=TEST_MOBILE_CLIENT_ID,
        access_token=access_token,
        name="Android User",
    )

    response = client.post("/auth/bootstrap", json={"id_token": id_token, "access_token": access_token})

    assert response.status_code == 200
    assert response.json() == {
        "id": 1,
        "cognito_sub": "android-sub",
        "email": "android@example.com",
        "display_name": "Android User",
    }


def test_one_account_works_from_web_and_mobile_clients_at_once(client: TestClient, cognito_tokens):
    api.app.dependency_overrides.pop(api.get_current_identity)
    web_access = cognito_tokens.access_token(sub="shared-sub", client_id=TEST_WEB_CLIENT_ID)
    mobile_access = cognito_tokens.access_token(sub="shared-sub", client_id=TEST_MOBILE_CLIENT_ID)
    client.post(
        "/auth/bootstrap",
        json={
            "id_token": cognito_tokens.id_token(sub="shared-sub", email="shared@example.com", access_token=web_access),
            "access_token": web_access,
        },
    )
    created = client.post(
        "/households",
        json={"name": "Primary Home", "zip_code": "80202", "home_type": "single_family"},
        headers={"Authorization": f"Bearer {web_access}"},
    )
    assert created.status_code == 201

    from_web = client.get("/households", headers={"Authorization": f"Bearer {web_access}"})
    from_mobile = client.get("/households", headers={"Authorization": f"Bearer {mobile_access}"})

    assert from_web.status_code == from_mobile.status_code == 200
    assert from_mobile.json() == from_web.json() == [created.json()]


def test_api_rejects_tokens_from_unknown_app_clients(client: TestClient, cognito_tokens):
    api.app.dependency_overrides.pop(api.get_current_identity)
    token = cognito_tokens.access_token(client_id="someone-elses-client")

    response = client.get("/households", headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 401


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


def test_auth_refresh_returns_renewed_tokens(client: TestClient, monkeypatch):
    refreshed_with: list[str] = []

    def fake_refresh(refresh_token: str) -> dict:
        refreshed_with.append(refresh_token)
        return {
            "id_token": "new-id-token",
            "access_token": "new-access-token",
            "expires_in": 3600,
            "token_type": "Bearer",
        }

    monkeypatch.setattr(api, "refresh_cognito_tokens", fake_refresh)

    response = client.post("/auth/refresh", json={"refresh_token": "refresh-token"})

    assert response.status_code == 200
    assert response.json() == {
        "id_token": "new-id-token",
        "access_token": "new-access-token",
        "expires_in": 3600,
        "token_type": "Bearer",
        "refresh_token": None,
    }
    assert refreshed_with == ["refresh-token"]


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
            "status": "ready",
        }
    ]


def _post_dishwasher_upload(client: TestClient, household_id: int):
    return client.post(
        "/documents/index",
        data={
            "household_id": str(household_id),
            "entry_id": "dishwasher-manual",
            "session_id": "session-1",
            "display_name": "Dishwasher Manual",
            "doc_type": "manual",
        },
        files={"file": ("manual.pdf", b"%PDF-1.4 fake", "application/pdf")},
    )


def _simulate_client_disconnect(monkeypatch) -> None:
    async def disconnect_immediately(request, cancelled):
        cancelled.set()

    monkeypatch.setattr(api, "_cancel_on_disconnect", disconnect_immediately)


def test_document_upload_cancelled_mid_index_is_not_saved(client: TestClient, monkeypatch):
    household = _create_household(client)
    _simulate_client_disconnect(monkeypatch)

    class CancellingIngestionService(StubIngestionService):
        def index(self, **kwargs) -> dict:
            self.calls.append(kwargs)
            if kwargs["should_cancel"]():
                raise IngestionCancelled("Indexing was cancelled before the document was saved.")
            return super().index(**kwargs)

    service = CancellingIngestionService()
    api.app.dependency_overrides[api.get_ingestion_service] = lambda: service

    response = _post_dishwasher_upload(client, household["id"])

    assert response.status_code == 499
    assert client.get("/documents", params={"household_id": household["id"]}).json() == []


def test_document_upload_discards_chunks_when_client_leaves_after_indexing(
    client: TestClient, monkeypatch
):
    household = _create_household(client)
    _simulate_client_disconnect(monkeypatch)
    service = StubIngestionService()
    api.app.dependency_overrides[api.get_ingestion_service] = lambda: service

    response = _post_dishwasher_upload(client, household["id"])

    assert response.status_code == 499
    assert service.discarded == [
        {"household_id": household["id"], "entry_id": "dishwasher-manual"}
    ]
    assert client.get("/documents", params={"household_id": household["id"]}).json() == []


def test_documents_list_responds_while_an_upload_is_indexing(client: TestClient):
    household = _create_household(client)
    list_status: list[int] = []

    class ListingIngestionService(StubIngestionService):
        def index(self, **kwargs) -> dict:
            # If indexing ran on the event loop, this request could not be served
            # until indexing finished, so the bounded wait below would expire.
            lister = threading.Thread(
                target=lambda: list_status.append(
                    client.get("/documents", params={"household_id": household["id"]}).status_code
                ),
                daemon=True,
            )
            lister.start()
            lister.join(timeout=5)
            return super().index(**kwargs)

    api.app.dependency_overrides[api.get_ingestion_service] = lambda: ListingIngestionService()

    response = _post_dishwasher_upload(client, household["id"])

    assert response.status_code == 200
    assert list_status == [200]


def test_query_persists_sanitized_messages_to_conversation_history(client: TestClient):
    household = _create_household(client)
    service = StubQueryService(
        {
            "answer": "Clean the dishwasher filter and run a hot cycle.",
            "sanitized_query": "How do I clean the dishwasher?",
            "input_blocked": False,
            "route": ["document_qa_agent"],
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
            "user_id": 1,
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
            "sources": [],
            "urgency_level": None,
            "should_escalate": False,
        },
        {
            "id": 2,
            "household_id": household["id"],
            "session_id": "session-42",
            "role": "assistant",
            "content": "Clean the dishwasher filter and run a hot cycle.",
            "created_at": messages_response.json()[1]["created_at"],
            "sources": [],
            "urgency_level": None,
            "should_escalate": False,
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


def test_streaming_query_emits_token_events_and_persists_final_turn(client: TestClient):
    household = _create_household(client)
    service = StubQueryService(
        result={},
        stream_events=[
            {
                "type": "user_accepted",
                "sanitized_query": "How do I clean the dishwasher?",
                "input_blocked": False,
            },
            {
                "type": "status",
                "message": "🧭 Routing complete: document_qa_agent",
            },
            {
                "type": "token",
                "text": "Clean ",
            },
            {
                "type": "token",
                "text": "the filter.",
            },
            {
                "type": "final",
                "result": {
                    "answer": "Clean the filter.",
                    "query": "How do I clean the dishwasher filter?",
                    "sanitized_query": "How do I clean the dishwasher?",
                    "input_blocked": False,
                    "route": ["document_qa_agent"],
                    "route_confidence": 0.91,
                    "route_explanation": "Manual troubleshooting request",
                    "urgency_level": None,
                    "should_escalate": False,
                    "case_draft": None,
                    "task_draft": None,
                    "contractor_suggestions": [],
                    "retrieval_context": [],
                },
            },
        ],
    )
    api.app.dependency_overrides[api.get_query_service] = lambda: service

    response = client.post(
        "/query/stream",
        json={
            "question": "How do I clean the dishwasher filter?",
            "session_id": "session-stream-1",
            "household_id": household["id"],
        },
    )

    assert response.status_code == 200
    sse_events = [
        line[6:]
        for line in response.text.splitlines()
        if line.startswith("data: ")
    ]
    assert [api.json.loads(item) for item in sse_events] == [
        {
            "v": 1,
            "type": "user_accepted",
            "sanitized_query": "How do I clean the dishwasher?",
            "input_blocked": False,
            "conversation_id": "session-stream-1",
            "message_id": 1,
        },
        {
            "v": 1,
            "type": "status",
            "message": "🧭 Routing complete: document_qa_agent",
        },
        {
            "v": 1,
            "type": "token",
            "text": "Clean ",
        },
        {
            "v": 1,
            "type": "token",
            "text": "the filter.",
        },
        {
            "v": 1,
            "type": "final",
            "message_id": 2,
            "result": {
                "answer": "Clean the filter.",
                "query": "How do I clean the dishwasher filter?",
                "sanitized_query": "How do I clean the dishwasher?",
                "input_blocked": False,
                "route": ["document_qa_agent"],
                "route_confidence": 0.91,
                "route_explanation": "Manual troubleshooting request",
                "urgency_level": None,
                "should_escalate": False,
                "case_draft": None,
                "task_draft": None,
                "contractor_suggestions": [],
                "retrieval_context": [],
                "sources": [],
            },
        },
    ]
    assert service.stream_calls == [
        {
            "user_query": "How do I clean the dishwasher filter?",
            "user_id": 1,
            "session_id": "session-stream-1",
            "entry_id": None,
            "household_id": household["id"],
            "asset_id": None,
            "household_zip_code": None,
            "messages": [],
        }
    ]

    messages_response = client.get(
        "/conversations/session-stream-1/messages",
        params={"household_id": household["id"]},
    )

    assert messages_response.status_code == 200
    assert messages_response.json() == [
        {
            "id": 1,
            "household_id": household["id"],
            "session_id": "session-stream-1",
            "role": "user",
            "content": "How do I clean the dishwasher?",
            "created_at": messages_response.json()[0]["created_at"],
            "sources": [],
            "urgency_level": None,
            "should_escalate": False,
        },
        {
            "id": 2,
            "household_id": household["id"],
            "session_id": "session-stream-1",
            "role": "assistant",
            "content": "Clean the filter.",
            "created_at": messages_response.json()[1]["created_at"],
            "sources": [],
            "urgency_level": None,
            "should_escalate": False,
        },
    ]
