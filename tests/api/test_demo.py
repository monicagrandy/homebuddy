"""The public demo: no sign-in, only the demo household's manuals, nothing saved, per-visitor limits."""

import json
from collections.abc import Generator
from datetime import date

import pytest
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage, HumanMessage
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from backend import api, rate_limit
from backend.db import Base
from backend.models import ConversationMessage, Document, Household, User
from backend.rate_limit import DailyQuota, demo_quota, limiter

FINAL_RESULT = {
    "answer": "Clean the filter.",
    "query": "How do I clean the filter?",
    "sanitized_query": "How do I clean the filter?",
    "input_blocked": False,
    "route": ["document_qa_agent"],
    "route_confidence": 0.9,
    "urgency_level": "low",
    "should_escalate": False,
    "contractor_suggestions": [],
    "retrieval_context": [],
}


class RecordingStreamService:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    def stream_query(self, **kwargs):
        self.calls.append(kwargs)
        yield {"type": "user_accepted", "sanitized_query": kwargs["user_query"], "input_blocked": False}
        yield {"type": "token", "text": "Clean the filter."}
        yield {"type": "final", "result": FINAL_RESULT}


@pytest.fixture(autouse=True)
def reset_limits() -> Generator[None, None, None]:
    limiter.reset()
    demo_quota.reset()
    api.app.dependency_overrides.clear()
    yield
    limiter.reset()
    demo_quota.reset()
    api.app.dependency_overrides.clear()


@pytest.fixture
def client(tmp_path, monkeypatch) -> Generator[TestClient, None, None]:
    """A demo household with one manual and another household with a private one; nobody signed in."""
    engine = create_engine(f"sqlite:///{tmp_path / 'demo.db'}", connect_args={"check_same_thread": False})
    session_local = sessionmaker(bind=engine, autoflush=False, autocommit=False)
    Base.metadata.create_all(engine)
    monkeypatch.setattr(api, "engine", engine)
    monkeypatch.setattr(api, "SessionLocal", session_local)
    monkeypatch.setattr(api.settings, "database_url", f"sqlite:///{tmp_path / 'demo.db'}")

    def override_get_session():
        with session_local() as session:
            yield session

    api.app.dependency_overrides[api.get_session] = override_get_session

    with session_local() as session:
        demo_home = Household(name="Home Buddy demo", zip_code="80202", home_type="house")
        other_home = Household(name="Someone's home", zip_code="10001", home_type="condo")
        session.add_all([demo_home, other_home])
        session.flush()
        session.add_all(
            [
                Document(
                    entry_id="bosch-dishwasher",
                    household_id=demo_home.id,
                    doc_type="manual",
                    source_name="Bosch dishwasher manual",
                    source_url="https://example.com/bosch.pdf",
                ),
                Document(
                    entry_id="private-receipt",
                    household_id=other_home.id,
                    doc_type="warranty",
                    source_name="My receipt",
                ),
            ]
        )
        session.commit()
        monkeypatch.setattr(api.settings, "demo_household_id", demo_home.id)

    with TestClient(api.app) as test_client:
        test_client.session_local = session_local
        yield test_client
    engine.dispose()


def _use_service() -> RecordingStreamService:
    service = RecordingStreamService()
    api.app.dependency_overrides[api.get_query_service] = lambda: service
    return service


def _ask(client: TestClient, **body):
    return client.post("/demo/query/stream", json={"question": "How do I clean the filter?", **body})


def _events(response) -> list[dict]:
    return [json.loads(line[6:]) for line in response.text.splitlines() if line.startswith("data: ")]


def test_documents_lists_only_the_demo_manuals_without_sign_in(client: TestClient):
    response = client.get("/demo/documents")

    assert response.status_code == 200
    assert [(d["entry_id"], d["display_name"], d["download_url"]) for d in response.json()] == [
        ("bosch-dishwasher", "Bosch dishwasher manual", "https://example.com/bosch.pdf")
    ]


def test_demo_is_unavailable_until_its_household_is_set(client: TestClient, monkeypatch):
    monkeypatch.setattr(api.settings, "demo_household_id", 0)

    assert client.get("/demo/documents").status_code == 404
    assert _ask(client).status_code == 404


def test_answers_come_from_the_demo_household(client: TestClient):
    service = _use_service()

    response = _ask(client)

    assert response.status_code == 200
    call = service.calls[0]
    assert call["household_id"] == api.settings.demo_household_id
    assert call["household_zip_code"] == "80202"
    assert call["user_id"] is None
    assert [event["type"] for event in _events(response)] == ["user_accepted", "token", "final"]


def test_asking_about_another_households_document_is_not_found(client: TestClient):
    service = _use_service()

    assert _ask(client, entry_id="private-receipt").status_code == 404
    assert _ask(client, entry_id="bosch-dishwasher").status_code == 200
    assert service.calls[0]["entry_id"] == "bosch-dishwasher"


def test_the_page_history_reaches_the_agent(client: TestClient):
    service = _use_service()

    _ask(
        client,
        history=[
            {"role": "user", "content": "Is the filter removable?"},
            {"role": "assistant", "content": "Yes, it twists out."},
        ],
    )

    assert service.calls[0]["messages"] == [
        HumanMessage(content="Is the filter removable?"),
        AIMessage(content="Yes, it twists out."),
    ]


def test_oversized_questions_and_histories_are_rejected(client: TestClient):
    _use_service()

    assert _ask(client, question="x" * 1001).status_code == 422
    assert _ask(client, history=[{"role": "user", "content": "hi"}] * 5).status_code == 422
    assert _ask(client, history=[{"role": "system", "content": "hi"}]).status_code == 422


def test_nothing_is_saved(client: TestClient):
    _use_service()

    assert _ask(client).status_code == 200

    with client.session_local() as session:
        assert session.query(User).count() == 0
        assert session.query(ConversationMessage).count() == 0


def test_unexpected_errors_reach_visitors_as_a_generic_message(client: TestClient):
    class FailingService:
        def stream_query(self, **kwargs):
            raise RuntimeError("connection string with a password in it")
            yield

    api.app.dependency_overrides[api.get_query_service] = lambda: FailingService()

    events = _events(_ask(client))

    assert events == [{"type": "error", "message": "Something went wrong while answering. Please try again."}]


def test_a_visitor_is_limited_per_minute(client: TestClient, monkeypatch):
    _use_service()
    monkeypatch.setattr(api.settings, "demo_queries_per_minute", 2)

    assert [_ask(client).status_code for _ in range(2)] == [200, 200]
    limited = _ask(client)
    assert limited.status_code == 429
    assert int(limited.headers["Retry-After"]) >= 1

    monkeypatch.setattr(rate_limit, "visitor_key", lambda request: "203.0.113.9")
    assert _ask(client).status_code == 200


def test_a_visitor_and_the_whole_demo_are_limited_per_day(client: TestClient, monkeypatch):
    _use_service()
    monkeypatch.setattr(api.settings, "demo_queries_per_visitor_per_day", 2)
    monkeypatch.setattr(api.settings, "demo_queries_per_day", 3)

    assert [_ask(client).status_code for _ in range(3)] == [200, 200, 429]

    monkeypatch.setattr(rate_limit, "visitor_key", lambda request: "203.0.113.9")
    assert [_ask(client).status_code for _ in range(2)] == [200, 429]
    assert "today" in _ask(client).json()["detail"]


def test_daily_quota_starts_again_each_day():
    today = [date(2026, 10, 29)]
    quota = DailyQuota(today=lambda: today[0])

    assert [quota.take("a", limit=2) for _ in range(3)] == [True, True, False]
    assert quota.take("b", limit=2) is True

    today[0] = date(2026, 10, 30)
    assert quota.take("a", limit=2) is True


def test_daily_quota_is_off_when_the_limit_is_zero():
    quota = DailyQuota()

    assert all(quota.take("a", limit=0) for _ in range(100))


@pytest.mark.parametrize(
    ("host", "key"),
    [
        ("203.0.113.9", "203.0.113.9"),
        ("2001:db8:1:2:aaaa::1", "2001:db8:1:2::/64"),
        ("testclient", "testclient"),
    ],
)
def test_visitor_key_groups_ipv6_by_network(host, key):
    class FakeRequest:
        client = type("Client", (), {"host": host})()

    assert rate_limit.visitor_key(FakeRequest()) == key
