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


import hashlib
import time
import uuid
from functools import lru_cache

import pytest

TEST_COGNITO_ISSUER = os.environ["COGNITO_ISSUER"]
TEST_WEB_CLIENT_ID = os.environ["COGNITO_APP_CLIENT_ID"]
TEST_MOBILE_CLIENT_ID = "test-android-client"
TEST_SIGNING_KID = "test-kid"


@lru_cache(maxsize=None)
def _rsa_key_pair(label: str) -> tuple[str, dict]:
    """Return (private PEM, public JWK); cached because RSA generation is slow."""
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from jose import jwk

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    private_pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ).decode()
    public_pem = key.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    public_jwk = jwk.construct(public_pem, "RS256").to_dict()
    return private_pem, public_jwk


class CognitoTokenFactory:
    """Signs Cognito-shaped tokens with a test key that backend.auth trusts.

    Passing a claim as None removes it from the token.
    """

    def __init__(self, private_pem: str, kid: str = TEST_SIGNING_KID) -> None:
        self._private_pem = private_pem
        self.kid = kid

    def _sign(self, claims: dict, kid: str | None) -> str:
        from jose import jwt

        claims = {name: value for name, value in claims.items() if value is not None}
        return jwt.encode(claims, self._private_pem, algorithm="RS256", headers={"kid": kid or self.kid})

    def access_token(
        self,
        *,
        sub: str = "user-sub",
        client_id: str = TEST_WEB_CLIENT_ID,
        groups: list[str] | None = None,
        kid: str | None = None,
        **overrides,
    ) -> str:
        now = int(time.time())
        claims = {
            "sub": sub,
            "iss": TEST_COGNITO_ISSUER,
            "client_id": client_id,
            "token_use": "access",
            "scope": "openid email profile",
            "username": sub,
            "jti": str(uuid.uuid4()),
            "auth_time": now,
            "iat": now,
            "exp": now + 3600,
            "cognito:groups": groups,
        }
        claims.update(overrides)
        return self._sign(claims, kid)

    def id_token(
        self,
        *,
        sub: str = "user-sub",
        email: str = "user@example.com",
        client_id: str = TEST_WEB_CLIENT_ID,
        access_token: str | None = None,
        groups: list[str] | None = None,
        kid: str | None = None,
        **overrides,
    ) -> str:
        from jose.utils import calculate_at_hash

        now = int(time.time())
        claims = {
            "sub": sub,
            "iss": TEST_COGNITO_ISSUER,
            "aud": client_id,
            "token_use": "id",
            "email": email,
            "email_verified": True,
            "auth_time": now,
            "iat": now,
            "exp": now + 3600,
            "cognito:groups": groups,
            "at_hash": calculate_at_hash(access_token, hashlib.sha256) if access_token else None,
        }
        claims.update(overrides)
        return self._sign(claims, kid)


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
