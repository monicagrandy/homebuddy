import base64

import pytest
from fastapi import HTTPException

from backend import auth


class _FakeResponse:
    def __init__(self, payload: dict):
        self._payload = payload

    def raise_for_status(self) -> None:
        return None

    def json(self) -> dict:
        return self._payload


def test_get_current_identity_requires_bearer_token():
    with pytest.raises(HTTPException, match="Missing Authorization header"):
        auth.get_current_identity(None)


def test_exchange_auth_code_for_tokens_uses_basic_auth_header(monkeypatch):
    captured: dict = {}

    monkeypatch.setattr(auth.settings, "cognito_domain", "https://example.auth.us-east-2.amazoncognito.com/")
    monkeypatch.setattr(auth.settings, "cognito_app_client_id", "client-123")
    monkeypatch.setattr(auth.settings, "cognito_app_client_secret", "secret-456")
    monkeypatch.setattr(auth.settings, "cognito_redirect_uri", "https://homebuddy.example.com")

    def fake_post(url: str, data: dict, headers: dict, timeout: float):
        captured["url"] = url
        captured["data"] = data
        captured["headers"] = headers
        captured["timeout"] = timeout
        return _FakeResponse(
            {
                "id_token": "id-token",
                "access_token": "access-token",
                "expires_in": 3600,
                "token_type": "Bearer",
            }
        )

    monkeypatch.setattr(auth.httpx, "post", fake_post)

    payload = auth.exchange_auth_code_for_tokens("auth-code")

    assert payload["id_token"] == "id-token"
    assert captured["url"] == "https://example.auth.us-east-2.amazoncognito.com/oauth2/token"
    assert captured["data"] == {
        "grant_type": "authorization_code",
        "client_id": "client-123",
        "code": "auth-code",
        "redirect_uri": "https://homebuddy.example.com",
    }
    assert captured["headers"]["Content-Type"] == "application/x-www-form-urlencoded"
    assert captured["headers"]["Authorization"] == "Basic " + base64.b64encode(
        b"client-123:secret-456"
    ).decode("utf-8")


def test_ensure_cognito_beta_access_allows_when_group_gate_is_disabled(monkeypatch):
    monkeypatch.setattr(auth.settings, "cognito_allowed_groups", ())

    auth.ensure_cognito_beta_access(auth.AuthenticatedIdentity(subject="sub-123"))


def test_ensure_cognito_beta_access_allows_matching_group(monkeypatch):
    monkeypatch.setattr(auth.settings, "cognito_allowed_groups", ("beta_testers",))

    auth.ensure_cognito_beta_access(
        auth.AuthenticatedIdentity(subject="sub-123", groups=["beta_testers"])
    )


def test_ensure_cognito_beta_access_rejects_non_beta_user(monkeypatch):
    monkeypatch.setattr(auth.settings, "cognito_allowed_groups", ("beta_testers",))

    with pytest.raises(HTTPException) as exc_info:
        auth.ensure_cognito_beta_access(
            auth.AuthenticatedIdentity(subject="sub-123", groups=["general_users"])
        )

    assert exc_info.value.status_code == 403
    assert exc_info.value.detail == "This account is not enabled for the HomeBuddy beta."
