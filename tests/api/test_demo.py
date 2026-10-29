"""The public demo: no sign-in, only the demo household's manuals, nothing saved, per-visitor limits."""

import json
from datetime import date

import pytest
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage, HumanMessage

from backend import api, rate_limit
from backend.models import Conversation, ConversationMessage, Document, Household, User
from backend.rate_limit import DailyQuota

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
    def __init__(self, *, sources=None) -> None:
        self.sources = sources or []
        self.calls: list[dict] = []

    def stream_query(self, **kwargs):
        self.calls.append(kwargs)
        yield {"type": "user_accepted", "sanitized_query": kwargs["user_query"], "input_blocked": False}
        yield {"type": "final", "result": {**FINAL_RESULT, "sources": self.sources}}


@pytest.fixture
def demo(client: TestClient, monkeypatch):
    """A demo household with one manual, another household with a private one, and no signed-in user."""
    with client.testing_session_local() as session:
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
        demo_id = demo_home.id
    monkeypatch.setattr(api.settings, "demo_household_id", demo_id)
    # The demo routes must not depend on the signed-in identity the client fixture provides.
    api.app.dependency_overrides.pop(api.get_current_identity)
    return demo_id


def _use_service(service: RecordingStreamService) -> RecordingStreamService:
    api.app.dependency_overrides[api.get_query_service] = lambda: service
    return service


def _ask(client: TestClient, **body):
    return client.post("/demo/query/stream", json={"question": "How do I clean the filter?", **body})


def _events(response) -> list[dict]:
    return [json.loads(line[6:]) for line in response.text.splitlines() if line.startswith("data: ")]


def test_documents_lists_only_the_demo_manuals_without_sign_in(client: TestClient, demo):
    response = client.get("/demo/documents")

    assert response.status_code == 200
    assert [(d["entry_id"], d["display_name"], d["download_url"]) for d in response.json()] == [
        ("bosch-dishwasher", "Bosch dishwasher manual", "https://example.com/bosch.pdf")
    ]


def test_demo_is_unavailable_until_its_household_is_set(client: TestClient):
    api.app.dependency_overrides.pop(api.get_current_identity)

    assert client.get("/demo/documents").status_code == 404
    assert _ask(client).status_code == 404


def test_answers_come_from_the_demo_household_and_cite_only_its_manuals(client: TestClient, demo):
    service = _use_service(
        RecordingStreamService(
            sources=[
                {"entry_id": "bosch-dishwasher", "page": 12, "doc_type": "manual"},
                {"entry_id": "private-receipt", "page": 1, "doc_type": "warranty"},
            ]
        )
    )

    response = _ask(client)

    assert response.status_code == 200
    call = service.calls[0]
    assert call["household_id"] == demo
    assert call["household_zip_code"] == "80202"
    assert call["user_id"] is None
    final = _events(response)[-1]
    assert final["type"] == "final"
    assert final["result"]["sources"] == [
        {
            "entry_id": "bosch-dishwasher",
            "display_name": "Bosch dishwasher manual",
            "page": 12,
            "doc_type": "manual",
            "url": "https://example.com/bosch.pdf",
        }
    ]


def test_asking_about_another_households_document_is_not_found(client: TestClient, demo):
    service = _use_service(RecordingStreamService())

    assert _ask(client, entry_id="private-receipt").status_code == 404
    assert _ask(client, entry_id="bosch-dishwasher").status_code == 200
    assert service.calls[0]["entry_id"] == "bosch-dishwasher"


def test_the_page_history_reaches_the_agent(client: TestClient, demo):
    service = _use_service(RecordingStreamService())

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


def test_oversized_questions_and_histories_are_rejected(client: TestClient, demo):
    _use_service(RecordingStreamService())

    assert _ask(client, question="x" * 1001).status_code == 422
    assert _ask(client, history=[{"role": "user", "content": "hi"}] * 5).status_code == 422
    assert _ask(client, history=[{"role": "system", "content": "hi"}]).status_code == 422


def test_nothing_is_saved(client: TestClient, demo):
    _use_service(RecordingStreamService())

    assert _ask(client).status_code == 200

    with client.testing_session_local() as session:
        assert session.query(User).count() == 0
        assert session.query(Conversation).count() == 0
        assert session.query(ConversationMessage).count() == 0


def test_a_visitor_is_limited_per_minute(client: TestClient, demo, monkeypatch):
    _use_service(RecordingStreamService())
    monkeypatch.setattr(api.settings, "demo_queries_per_minute", 2)

    assert [_ask(client).status_code for _ in range(2)] == [200, 200]
    limited = _ask(client)
    assert limited.status_code == 429
    assert int(limited.headers["Retry-After"]) >= 1

    monkeypatch.setattr(rate_limit, "visitor_key", lambda request: "203.0.113.9")
    assert _ask(client).status_code == 200


def test_a_visitor_and_the_whole_demo_are_limited_per_day(client: TestClient, demo, monkeypatch):
    _use_service(RecordingStreamService())
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
