import hb_test_env  # noqa: F401  (must run before backend is imported)

import time

import pytest

from cognito_fakes import (
    TEST_COGNITO_ISSUER,
    TEST_MOBILE_CLIENT_ID,
    TEST_SIGNING_KID,
    TEST_WEB_CLIENT_ID,
    CognitoTokenFactory,
    _rsa_key_pair,
)


@pytest.fixture
def cognito_tokens(monkeypatch) -> CognitoTokenFactory:
    """Make backend.auth trust a local signing key, with no network calls."""
    from backend import auth

    private_pem, public_jwk = _rsa_key_pair("trusted")
    jwks = {"keys": [{**public_jwk, "kid": TEST_SIGNING_KID, "use": "sig", "alg": "RS256"}]}

    class _JwksResponse:
        def raise_for_status(self) -> None:
            return None

        def json(self) -> dict:
            return jwks

    monkeypatch.setitem(auth._jwks_cache, "jwks", jwks)
    monkeypatch.setitem(auth._jwks_cache, "expires_at", time.time() + 3600)
    monkeypatch.setattr(auth.httpx, "get", lambda *_args, **_kwargs: _JwksResponse())
    monkeypatch.setattr(auth.settings, "cognito_issuer", TEST_COGNITO_ISSUER)
    monkeypatch.setattr(auth.settings, "cognito_app_client_id", TEST_WEB_CLIENT_ID)
    monkeypatch.setattr(auth.settings, "cognito_mobile_app_client_ids", (TEST_MOBILE_CLIENT_ID,))
    return CognitoTokenFactory(private_pem)


@pytest.fixture
def untrusted_cognito_tokens() -> CognitoTokenFactory:
    """Same kid as the trusted key, different private key: signatures must fail."""
    private_pem, _ = _rsa_key_pair("untrusted")
    return CognitoTokenFactory(private_pem)
