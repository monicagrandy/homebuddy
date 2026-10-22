import os


os.environ.setdefault("OPENAI_API_KEY", "test-key")
os.environ.setdefault("DATABASE_URL", "sqlite:///./test_home_buddy.db")
os.environ.setdefault("VECTOR_STORE_PROVIDER", "chroma")
os.environ.setdefault("CONTRACTOR_SEARCH_PROVIDER", "mock")
os.environ.setdefault("HOME_OPERATIONS_ENABLED", "true")
os.environ["COGNITO_ISSUER"] = "https://cognito-idp.us-east-2.amazonaws.com/us-east-2_testpool"
os.environ["COGNITO_JWKS_URL"] = "https://cognito-idp.us-east-2.amazonaws.com/us-east-2_testpool/.well-known/jwks.json"
os.environ["COGNITO_APP_CLIENT_ID"] = "test-web-client"
os.environ["COGNITO_ALLOWED_GROUPS"] = ""
os.environ["WARM_RUNTIME_ON_STARTUP"] = "false"
os.environ["LANGCHAIN_TRACING_V2"] = "false"
os.environ["LANGCHAIN_TRACING_V2_TRACING"] = "false"
os.environ["LANGSMITH_API_KEY"] = ""
os.environ["ENVIRONMENT"] = "test"
os.environ["ALLOWED_HOSTS"] = ""
# Rate limits are off unless a test turns them on (tests/api/test_rate_limit.py).
os.environ["RATE_LIMIT_QUERIES_PER_MINUTE"] = "0"
os.environ["RATE_LIMIT_DOCUMENTS_PER_MINUTE"] = "0"
# Tests run indexing jobs explicitly with DocumentJobRunner.run_next().
os.environ["DOCUMENT_JOBS_ENABLED"] = "false"


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
