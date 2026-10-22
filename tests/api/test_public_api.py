"""Behavior that matters once the API is reachable from the internet."""

import json

import pytest
from fastapi.testclient import TestClient

from backend import api
from backend.middleware import host_is_allowed
from backend.models import ConversationMessage


def test_docs_and_schema_are_hidden_in_production():
    assert api.api_docs_options(production=True) == {"docs_url": None, "redoc_url": None, "openapi_url": None}
    assert api.api_docs_options(production=False) == {}


def test_schema_is_served_outside_production(client: TestClient):
    assert client.get("/openapi.json").status_code == 200


@pytest.mark.parametrize(
    ("host", "allowed", "expected"),
    [
        ("api.homebuddy.chat", ("api.homebuddy.chat", "backend"), True),
        ("backend", ("api.homebuddy.chat", "backend"), True),
        ("API.HomeBuddy.chat", ("api.homebuddy.chat",), True),
        ("evil.example.com", ("api.homebuddy.chat", "backend"), False),
        ("homebuddy.chat.evil.com", ("*.homebuddy.chat",), False),
        ("staging.homebuddy.chat", ("*.homebuddy.chat",), True),
        ("anything", (), True),
        ("anything", ("*",), True),
    ],
)
def test_host_matching(host, allowed, expected):
    assert host_is_allowed(host, allowed) is expected


def test_requests_for_unknown_hosts_are_rejected(client: TestClient, monkeypatch):
    monkeypatch.setattr(api.settings, "allowed_hosts", ("api.homebuddy.chat", "backend"))

    rejected = client.get("/health", headers={"host": "evil.example.com"})
    allowed = client.get("/health", headers={"host": "backend:8000"})

    assert rejected.status_code == 400
    assert allowed.status_code == 200


class FailingStreamService:
    def stream_query(self, **kwargs):
        yield {"type": "user_accepted", "sanitized_query": kwargs["user_query"], "input_blocked": False}
        raise RuntimeError("could not connect to postgresql://homebuddy:hunter2@db:5432")


def test_unexpected_stream_errors_hide_internal_details(client: TestClient):
    client.get("/auth/me")
    household_id = client.post(
        "/households", json={"name": "Home", "zip_code": "80202", "home_type": "condo"}
    ).json()["id"]
    api.app.dependency_overrides[api.get_query_service] = FailingStreamService

    response = client.post(
        "/query/stream",
        json={"question": "Why is it beeping?", "session_id": "s-err", "household_id": household_id},
    )

    events = [json.loads(line[6:]) for line in response.text.splitlines() if line.startswith("data: ")]
    assert events[-1] == {"type": "error", "message": "Something went wrong while answering. Please try again."}
    assert "hunter2" not in response.text
    with client.testing_session_local() as session:
        saved = [message.content for message in session.query(ConversationMessage).all()]
    assert saved == ["Why is it beeping?", "Something went wrong while answering. Please try again."]
