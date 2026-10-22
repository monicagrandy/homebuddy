from fastapi.testclient import TestClient

from backend import api
from backend.auth import AuthenticatedIdentity
from backend.rate_limit import RateLimiter


class FakeClock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


class StubIngestionService:
    def index(self, **kwargs) -> dict:
        return {"entry_id": kwargs["entry_id"], "chunks_indexed": 1}

    def discard(self, **kwargs) -> None:
        return None


class AnsweringQueryService:
    def run_query(self, **kwargs) -> dict:
        return {
            "answer": "Clean the filter.",
            "sanitized_query": kwargs["user_query"],
            "input_blocked": False,
            "route": ["document_qa_agent"],
            "route_confidence": 0.9,
        }


def test_limiter_allows_a_burst_then_reports_the_wait():
    clock = FakeClock()
    limiter = RateLimiter(clock=clock)

    assert [limiter.check("query", "user-a", per_minute=3) for _ in range(3)] == [None, None, None]
    assert limiter.check("query", "user-a", per_minute=3) == 20.0


def test_limiter_refills_over_time():
    clock = FakeClock()
    limiter = RateLimiter(clock=clock)
    for _ in range(3):
        limiter.check("query", "user-a", per_minute=3)

    clock.now += 20
    assert limiter.check("query", "user-a", per_minute=3) is None
    assert limiter.check("query", "user-a", per_minute=3) is not None


def test_limiter_keeps_users_and_buckets_separate():
    limiter = RateLimiter(clock=FakeClock())
    limiter.check("query", "user-a", per_minute=1)

    assert limiter.check("query", "user-a", per_minute=1) is not None
    assert limiter.check("query", "user-b", per_minute=1) is None
    assert limiter.check("documents", "user-a", per_minute=1) is None


def test_limiter_is_off_when_the_limit_is_zero():
    limiter = RateLimiter(clock=FakeClock())

    assert all(limiter.check("query", "user-a", per_minute=0) is None for _ in range(100))


def _create_household(client: TestClient) -> int:
    client.get("/auth/me")
    response = client.post("/households", json={"name": "Home", "zip_code": "80202", "home_type": "condo"})
    return response.json()["id"]


def _ask(client: TestClient, household_id: int):
    return client.post(
        "/query",
        json={"question": "How do I clean the filter?", "session_id": "s-1", "household_id": household_id},
    )


def test_query_returns_429_with_retry_after_once_the_limit_is_used(client: TestClient, monkeypatch):
    monkeypatch.setattr(api.settings, "rate_limit_queries_per_minute", 2)
    api.app.dependency_overrides[api.get_query_service] = AnsweringQueryService
    household_id = _create_household(client)

    first, second, third = (_ask(client, household_id) for _ in range(3))

    assert first.status_code == second.status_code == 200
    assert third.status_code == 429
    assert int(third.headers["retry-after"]) >= 1
    assert third.json() == {"detail": "Too many requests. Please wait a moment and try again."}


def test_streaming_query_shares_the_query_limit(client: TestClient, monkeypatch):
    monkeypatch.setattr(api.settings, "rate_limit_queries_per_minute", 1)
    api.app.dependency_overrides[api.get_query_service] = AnsweringQueryService
    household_id = _create_household(client)
    _ask(client, household_id)

    response = client.post(
        "/query/stream",
        json={"question": "And the spray arm?", "session_id": "s-1", "household_id": household_id},
    )

    assert response.status_code == 429


def test_one_users_limit_does_not_affect_another(client: TestClient, monkeypatch):
    monkeypatch.setattr(api.settings, "rate_limit_queries_per_minute", 1)
    api.app.dependency_overrides[api.get_query_service] = AnsweringQueryService
    household_id = _create_household(client)
    assert _ask(client, household_id).status_code == 200
    assert _ask(client, household_id).status_code == 429

    api.app.dependency_overrides[api.get_current_identity] = lambda: AuthenticatedIdentity(
        subject="other-user-sub", email="other@example.com"
    )
    other_household_id = _create_household(client)

    assert _ask(client, other_household_id).status_code == 200


def test_document_indexing_has_its_own_limit(client: TestClient, monkeypatch):
    monkeypatch.setattr(api.settings, "rate_limit_documents_per_minute", 1)
    api.app.dependency_overrides[api.get_ingestion_service] = StubIngestionService
    household_id = _create_household(client)
    form = {"household_id": str(household_id), "entry_id": "manual", "session_id": "s-1", "doc_type": "manual"}
    files = {"file": ("manual.pdf", b"%PDF-1.4", "application/pdf")}

    first = client.post("/documents/index", data=form, files=files)
    second = client.post("/documents/index", data=form, files=files)

    assert first.status_code == 200
    assert second.status_code == 429
