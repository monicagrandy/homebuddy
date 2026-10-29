"""Streaming behavior phones depend on: keepalives, answers that survive disconnects, structured sources."""

import json
import threading
import time

from fastapi.testclient import TestClient

from backend import api
from backend.auth import AuthenticatedIdentity
from backend.models import ConversationMessage, Document
from backend.schemas import QueryRequest

FINAL_RESULT = {
    "answer": "Clean the filter.",
    "query": "How do I clean the filter?",
    "sanitized_query": "How do I clean the filter?",
    "input_blocked": False,
    "route": ["document_qa_agent"],
    "route_confidence": 0.9,
    "route_explanation": None,
    "urgency_level": None,
    "should_escalate": False,
    "case_draft": None,
    "task_draft": None,
    "contractor_suggestions": [],
    "retrieval_context": [],
    "sources": [],
}


class ScriptedStreamService:
    """Yields user_accepted, optionally waits, then yields final with the given raw sources."""

    def __init__(self, *, sources=None, pause: float = 0.0, gate: threading.Event | None = None) -> None:
        self.sources = sources or []
        self.pause = pause
        self.gate = gate
        self.finished = threading.Event()

    def stream_query(self, **kwargs):
        yield {"type": "user_accepted", "sanitized_query": kwargs["user_query"], "input_blocked": False}
        if self.pause:
            time.sleep(self.pause)
        if self.gate is not None:
            assert self.gate.wait(timeout=5), "test never released the gate"
        yield {"type": "final", "result": {**FINAL_RESULT, "sources": self.sources}}
        self.finished.set()

    def run_query(self, **kwargs):
        return {**FINAL_RESULT, "sources": self.sources}


def _setup_household(client: TestClient) -> int:
    client.get("/auth/me")
    return client.post("/households", json={"name": "Home", "zip_code": "80202", "home_type": "condo"}).json()["id"]


def _events(response) -> list[dict]:
    return [json.loads(line[6:]) for line in response.text.splitlines() if line.startswith("data: ")]


def _saved_messages(client: TestClient) -> list[tuple[str, str]]:
    with client.testing_session_local() as session:
        return [(m.role, m.content) for m in session.query(ConversationMessage).order_by(ConversationMessage.id)]


def test_stream_sends_pings_while_the_answer_is_being_prepared(client: TestClient, monkeypatch):
    monkeypatch.setattr(api, "_SSE_PING_SECONDS", 0.02)
    household_id = _setup_household(client)
    api.app.dependency_overrides[api.get_query_service] = lambda: ScriptedStreamService(pause=0.2)

    response = client.post(
        "/query/stream",
        json={"question": "How do I clean the filter?", "session_id": "s-ping", "household_id": household_id},
    )

    assert response.headers["content-type"].startswith("text/event-stream")
    assert response.headers["cache-control"] == "no-cache"
    assert ": ping" in response.text.splitlines()
    assert [event["type"] for event in _events(response)] == ["user_accepted", "final"]


def test_answer_is_saved_even_if_the_client_never_reads_the_stream(client: TestClient):
    household_id = _setup_household(client)
    gate = threading.Event()
    service = ScriptedStreamService(gate=gate)

    with client.testing_session_local() as session:
        user = session.query(api.User).one()
        # Call the route directly and never iterate its body, like a phone that went to the background.
        api.query_stream(
            payload=QueryRequest(question="How do I clean the filter?", session_id="s-gone", household_id=household_id),
            service=service,
            session=session,
            identity=AuthenticatedIdentity(subject=user.cognito_sub, email=user.email),
        )

    gate.set()
    assert service.finished.wait(timeout=5)
    deadline = time.monotonic() + 5
    while ("assistant", "Clean the filter.") not in _saved_messages(client) and time.monotonic() < deadline:
        time.sleep(0.02)

    assert _saved_messages(client) == [("user", "How do I clean the filter?"), ("assistant", "Clean the filter.")]


def _add_document(client: TestClient, household_id: int, entry_id: str, name: str, url: str | None = None) -> None:
    with client.testing_session_local() as session:
        session.add(
            Document(
                entry_id=entry_id,
                household_id=household_id,
                doc_type="manual",
                source_name=name,
                source_url=url,
            )
        )
        session.commit()


RAW_SOURCES = [
    {"entry_id": "bosch-manual", "source": "bosch-manual", "page": 12, "doc_type": "manual"},
    {"entry_id": "bosch-manual", "source": "bosch-manual", "page": 12, "doc_type": "manual"},
    {"entry_id": "ge-washer", "source": "https://example.com/ge.pdf", "page": 3, "doc_type": None},
    {"entry_id": "deleted-elsewhere", "source": "deleted-elsewhere", "page": 1, "doc_type": "manual"},
]

EXPECTED_SOURCES = [
    {
        "entry_id": "bosch-manual",
        "display_name": "Bosch Dishwasher Manual",
        "page": 12,
        "doc_type": "manual",
        "url": None,
    },
    {
        "entry_id": "ge-washer",
        "display_name": "GE Washer",
        "page": 3,
        "doc_type": "manual",
        "url": "https://example.com/ge.pdf",
    },
]


def test_stream_final_event_carries_resolved_sources(client: TestClient):
    household_id = _setup_household(client)
    _add_document(client, household_id, "bosch-manual", "Bosch Dishwasher Manual")
    _add_document(client, household_id, "ge-washer", "GE Washer", url="https://example.com/ge.pdf")
    api.app.dependency_overrides[api.get_query_service] = lambda: ScriptedStreamService(sources=RAW_SOURCES)

    response = client.post(
        "/query/stream",
        json={"question": "How do I clean the filter?", "session_id": "s-src", "household_id": household_id},
    )

    final = _events(response)[-1]
    assert final["type"] == "final"
    assert final["result"]["sources"] == EXPECTED_SOURCES


def test_query_response_carries_resolved_sources(client: TestClient):
    household_id = _setup_household(client)
    _add_document(client, household_id, "bosch-manual", "Bosch Dishwasher Manual")
    _add_document(client, household_id, "ge-washer", "GE Washer", url="https://example.com/ge.pdf")
    api.app.dependency_overrides[api.get_query_service] = lambda: ScriptedStreamService(sources=RAW_SOURCES)

    response = client.post(
        "/query",
        json={"question": "How do I clean the filter?", "session_id": "s-src", "household_id": household_id},
    )

    assert response.status_code == 200
    assert response.json()["sources"] == EXPECTED_SOURCES


class UrgentStreamService(ScriptedStreamService):
    def stream_query(self, **kwargs):
        yield {"type": "user_accepted", "sanitized_query": kwargs["user_query"], "input_blocked": False}
        yield {"type": "final", "result": self.run_query(**kwargs)}

    def run_query(self, **kwargs):
        return {**FINAL_RESULT, "sources": self.sources, "urgency_level": "critical", "should_escalate": True}


def test_reopened_history_shows_the_streamed_answers_sources_and_urgency(client: TestClient):
    household_id = _setup_household(client)
    _add_document(client, household_id, "bosch-manual", "Bosch Dishwasher Manual")
    _add_document(client, household_id, "ge-washer", "GE Washer", url="https://example.com/ge.pdf")
    api.app.dependency_overrides[api.get_query_service] = lambda: UrgentStreamService(sources=RAW_SOURCES)

    final = _events(
        client.post("/query/stream", json={"question": "I smell gas", "session_id": "s-details", "household_id": household_id})
    )[-1]
    question, answer = client.get("/conversations/s-details/messages", params={"household_id": household_id}).json()

    assert answer["sources"] == final["result"]["sources"] == EXPECTED_SOURCES
    assert (answer["urgency_level"], answer["should_escalate"]) == ("critical", True)
    assert (question["sources"], question["urgency_level"], question["should_escalate"]) == ([], None, False)


def test_reopened_history_shows_a_non_streamed_answers_sources_and_urgency(client: TestClient):
    household_id = _setup_household(client)
    _add_document(client, household_id, "bosch-manual", "Bosch Dishwasher Manual")
    _add_document(client, household_id, "ge-washer", "GE Washer", url="https://example.com/ge.pdf")
    api.app.dependency_overrides[api.get_query_service] = lambda: UrgentStreamService(sources=RAW_SOURCES)

    client.post("/query", json={"question": "I smell gas", "session_id": "s-details", "household_id": household_id})
    answer = client.get("/conversations/s-details/messages", params={"household_id": household_id}).json()[-1]

    assert answer["sources"] == EXPECTED_SOURCES
    assert (answer["urgency_level"], answer["should_escalate"]) == ("critical", True)


def test_answers_saved_without_details_have_none(client: TestClient):
    household_id = _setup_household(client)
    api.app.dependency_overrides[api.get_query_service] = lambda: ScriptedStreamService()
    client.post("/query/stream", json={"question": "Hello?", "session_id": "s-old", "household_id": household_id})
    with client.testing_session_local() as session:
        # Saved before answers kept their details.
        session.query(ConversationMessage).update({"sources": None, "urgency_level": None})
        session.commit()

    answer = client.get("/conversations/s-old/messages", params={"household_id": household_id}).json()[-1]

    assert (answer["sources"], answer["urgency_level"], answer["should_escalate"]) == ([], None, False)


def test_sources_never_include_another_households_documents(client: TestClient):
    household_id = _setup_household(client)
    other_household_id = client.post(
        "/households", json={"name": "Rental", "zip_code": "80202", "home_type": "condo"}
    ).json()["id"]
    _add_document(client, other_household_id, "bosch-manual", "Someone else's manual")

    with client.testing_session_local() as session:
        resolved = api._resolve_sources(session, household_id=household_id, raw_sources=RAW_SOURCES)

    assert resolved == []


def test_stream_message_ids_match_persisted_history_even_for_repeated_text(client: TestClient):
    household_id = _setup_household(client)
    api.app.dependency_overrides[api.get_query_service] = lambda: ScriptedStreamService()
    streamed_ids = []
    for _ in range(2):
        response = client.post(
            "/query/stream",
            json={"question": "Same question", "session_id": "s-ids", "household_id": household_id},
        )
        events = _events(response)
        streamed_ids.extend(event["message_id"] for event in events)
    history = client.get("/conversations/s-ids/messages", params={"household_id": household_id}).json()

    assert streamed_ids == [message["id"] for message in history]
    assert len(set(streamed_ids)) == 4
    assert [message["role"] for message in history] == ["user", "assistant", "user", "assistant"]


def test_stream_error_identifies_the_saved_error_message(client: TestClient):
    class FailingService:
        def stream_query(self, **kwargs):
            yield {"type": "user_accepted", "sanitized_query": kwargs["user_query"], "input_blocked": False}
            raise ValueError("Could not answer")

    household_id = _setup_household(client)
    api.app.dependency_overrides[api.get_query_service] = FailingService
    response = client.post(
        "/query/stream", json={"question": "Hello?", "session_id": "s-error-id", "household_id": household_id}
    )
    events = _events(response)
    history = client.get("/conversations/s-error-id/messages", params={"household_id": household_id}).json()

    assert events[-1]["type"] == "error"
    assert [event["message_id"] for event in events] == [message["id"] for message in history]
    assert events[-1]["message"] == history[-1]["content"]
