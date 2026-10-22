"""Conversations across devices: per-user lists, sync, the one-question lock, and deletes from elsewhere."""

import threading
import time
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from backend import api
from backend.auth import AuthenticatedIdentity
from backend.models import Conversation, ConversationMessage, HouseholdMember, User
from backend.schemas import QueryRequest
from backend.services import conversations as conversation_store


class AnsweringService:
    def __init__(self, answer: str = "Clean the filter.") -> None:
        self.answer = answer

    def run_query(self, **kwargs) -> dict:
        return {"answer": self.answer, "sanitized_query": kwargs["user_query"], "input_blocked": False}

    def stream_query(self, **kwargs):
        yield {"type": "user_accepted", "sanitized_query": kwargs["user_query"], "input_blocked": False}
        yield {"type": "final", "result": {"answer": self.answer, "sources": []}}


class FailingService:
    def run_query(self, **_kwargs) -> dict:
        raise ValueError("Question was empty.")


@pytest.fixture
def household_id(client: TestClient) -> int:
    client.get("/auth/me")
    api.app.dependency_overrides[api.get_query_service] = AnsweringService
    return client.post("/households", json={"name": "Home", "zip_code": "80202", "home_type": "condo"}).json()["id"]


def _ask(client, household_id, session_id, question="How do I clean the dishwasher filter?"):
    return client.post("/query", json={"question": question, "session_id": session_id, "household_id": household_id})


def _list(client, household_id, **params):
    return client.get("/conversations", params={"household_id": household_id, **params})


def _conversation(client, session_id) -> Conversation | None:
    with client.testing_session_local() as session:
        return session.query(Conversation).filter_by(session_id=session_id).one_or_none()


def _join_as_second_member(client, household_id) -> None:
    """Add another user to the household and make requests as them (sharing itself comes later)."""
    with client.testing_session_local() as session:
        other = User(cognito_sub="other-sub", email="other@example.com")
        session.add(other)
        session.flush()
        session.add(HouseholdMember(household_id=household_id, user_id=other.id, role="member"))
        session.commit()
    api.app.dependency_overrides[api.get_current_identity] = lambda: AuthenticatedIdentity(
        subject="other-sub", email="other@example.com"
    )


def test_asking_creates_a_titled_conversation_in_the_list(client, household_id):
    _ask(client, household_id, "thread-1", "How do I clean the dishwasher filter? It smells bad after every cycle lately")

    listed = _list(client, household_id).json()

    assert [(c["id"], c["title"]) for c in listed] == [
        ("thread-1", "How do I clean the dishwasher filter? It smells bad after…")
    ]
    assert listed[0]["created_at"].endswith("Z") or "+00:00" in listed[0]["created_at"]


def test_the_most_recently_used_conversation_comes_first(client, household_id):
    _ask(client, household_id, "older")
    _ask(client, household_id, "newer")
    _ask(client, household_id, "older", "And the spray arm?")

    assert [c["id"] for c in _list(client, household_id).json()] == ["older", "newer"]


def test_updated_since_returns_only_conversations_that_changed(client, household_id):
    _ask(client, household_id, "unchanged")
    _ask(client, household_id, "changed")
    checkpoint = _list(client, household_id).json()[0]["updated_at"]
    time.sleep(0.01)
    _ask(client, household_id, "changed", "One more thing")

    changed = _list(client, household_id, updated_since=checkpoint).json()

    assert [c["id"] for c in changed] == ["changed"]


def test_another_member_cannot_see_or_delete_my_conversation(client, household_id):
    _ask(client, household_id, "mine")
    _join_as_second_member(client, household_id)

    assert _list(client, household_id).json() == []
    assert client.get("/conversations/mine/messages", params={"household_id": household_id}).status_code == 404
    assert client.delete("/conversations/mine", params={"household_id": household_id}).status_code == 404
    assert _ask(client, household_id, "mine").status_code == 404
    assert _conversation(client, "mine") is not None


def test_a_second_question_while_one_is_being_answered_gets_409(client, household_id):
    _ask(client, household_id, "busy")
    with client.testing_session_local() as session:
        assert conversation_store.try_start_query(session, _conversation(client, "busy").id)

    response = _ask(client, household_id, "busy", "Are you there?")
    streamed = client.post(
        "/query/stream", json={"question": "Hello?", "session_id": "busy", "household_id": household_id}
    )

    assert response.status_code == streamed.status_code == 409
    assert response.json() == {"detail": "Still answering your last question in this conversation. Try again in a moment."}


def test_a_lock_left_by_a_dead_request_expires(client, household_id):
    _ask(client, household_id, "stale")
    with client.testing_session_local() as session:
        conversation = session.query(Conversation).filter_by(session_id="stale").one()
        conversation.active_query_started_at = datetime.now(timezone.utc) - timedelta(minutes=6)
        session.commit()

    assert _ask(client, household_id, "stale", "Still there?").status_code == 200


def test_the_lock_is_released_after_answers_and_after_errors(client, household_id):
    assert _ask(client, household_id, "seq").status_code == 200
    assert _ask(client, household_id, "seq", "Next question").status_code == 200

    api.app.dependency_overrides[api.get_query_service] = FailingService
    assert _ask(client, household_id, "seq", "").status_code == 400
    api.app.dependency_overrides[api.get_query_service] = AnsweringService

    assert _ask(client, household_id, "seq", "After the error").status_code == 200
    assert _conversation(client, "seq").active_query_started_at is None


def test_the_lock_is_released_after_a_streamed_answer(client, household_id):
    client.post("/query/stream", json={"question": "Hi", "session_id": "streamed", "household_id": household_id})

    assert _conversation(client, "streamed").active_query_started_at is None
    assert [c["title"] for c in _list(client, household_id).json()] == ["Hi"]


class GatedService:
    def __init__(self) -> None:
        self.gate = threading.Event()
        self.finished = threading.Event()

    def stream_query(self, **kwargs):
        yield {"type": "user_accepted", "sanitized_query": kwargs["user_query"], "input_blocked": False}
        assert self.gate.wait(timeout=5)
        yield {"type": "final", "result": {"answer": "Clean the filter.", "sources": []}}
        self.finished.set()


def test_a_conversation_deleted_on_another_device_mid_answer_stays_deleted(client, household_id):
    service = GatedService()
    with client.testing_session_local() as session:
        user = session.query(User).filter_by(cognito_sub="dev-user-sub").one()
        api.query_stream(
            payload=QueryRequest(question="How do I clean it?", session_id="doomed", household_id=household_id),
            service=service,
            session=session,
            identity=AuthenticatedIdentity(subject=user.cognito_sub, email=user.email),
        )
    deadline = time.monotonic() + 5
    while _conversation(client, "doomed").title is None and time.monotonic() < deadline:
        time.sleep(0.01)

    assert client.delete("/conversations/doomed", params={"household_id": household_id}).status_code == 204
    service.gate.set()
    assert service.finished.wait(timeout=5)
    time.sleep(0.1)

    assert _conversation(client, "doomed") is None
    with client.testing_session_local() as session:
        assert session.query(ConversationMessage).filter_by(session_id="doomed").count() == 0
    assert client.get("/conversations/doomed/messages", params={"household_id": household_id}).json() == []
    assert client.delete("/conversations/doomed", params={"household_id": household_id}).status_code == 404


def test_the_web_apps_message_routes_keep_working(client, household_id):
    _ask(client, household_id, "web-thread")

    messages = client.get("/conversations/web-thread/messages", params={"household_id": household_id}).json()
    assert [(m["role"], m["content"]) for m in messages] == [
        ("user", "How do I clean the dishwasher filter?"),
        ("assistant", "Clean the filter."),
    ]

    assert client.delete("/conversations/web-thread/messages", params={"household_id": household_id}).status_code == 204
    assert _list(client, household_id).json() == []
    assert client.get("/conversations/unknown/messages", params={"household_id": household_id}).json() == []
