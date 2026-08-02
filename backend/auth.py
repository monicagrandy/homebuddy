import base64
import json
import time

import httpx
from fastapi import Header, HTTPException, status
from jose import JWTError, jwt
from pydantic import BaseModel, Field

from backend.config import get_logger, settings

logger = get_logger(__name__)
JWKS_TTL_SECONDS = 60 * 60
_jwks_cache: dict[str, float | dict | None] = {
    "expires_at": 0.0,
    "jwks": None,
}

class AuthenticatedIdentity(BaseModel):
    subject: str
    email: str | None = None
    display_name: str | None = None
    groups: list[str] = Field(default_factory=list)


def _normalize_cognito_groups(raw_groups) -> list[str]:
    if raw_groups is None:
        return []
    if isinstance(raw_groups, str):
        value = raw_groups.strip()
        return [value] if value else []
    if isinstance(raw_groups, list):
        return [str(group).strip() for group in raw_groups if str(group).strip()]
    return []


def ensure_cognito_beta_access(identity: AuthenticatedIdentity) -> None:
    allowed_groups = settings.cognito_allowed_groups
    if not allowed_groups:
        return

    if any(group in allowed_groups for group in identity.groups):
        return

    logger.warning(
        "Beta access denied for cognito_sub=%s email=%s token_groups=%s allowed_groups=%s",
        identity.subject,
        identity.email,
        identity.groups,
        list(allowed_groups),
    )

    raise HTTPException(
        status_code=status.HTTP_403_FORBIDDEN,
        detail="This account is not enabled for the HomeBuddy beta.",
    )
    
def _extract_bearer_token(authorization: str | None) -> str:
    if not authorization:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing Authorization header.",
        )

    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid Authorization header. Expected 'Bearer <token>'.",
        )

    return token


def _token_endpoint_url() -> str:
    if not settings.cognito_domain:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Cognito domain is not configured.",
        )
    return f"{settings.cognito_domain.rstrip('/')}/oauth2/token"

def _load_jwks(*, force_refresh: bool = False) -> dict:
    now = time.time()
    cached_jwks = _jwks_cache["jwks"]
    expires_at = float(_jwks_cache["expires_at"])
    if not force_refresh and isinstance(cached_jwks, dict) and now < expires_at:
        return cached_jwks

    jwks_url = _require_cognito_setting(
        settings.cognito_jwks_url,
        field_name="COGNITO_JWKS_URL",
    )

    try:
        response = httpx.get(jwks_url, timeout=10.0)
        response.raise_for_status()
        jwks = response.json()
    except httpx.HTTPError as exc:
        logger.error("Failed to load Cognito JWK")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Unable to load JWK.",
        ) from exc
    except json.JSONDecodeError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Unable to parse JWK response.",
        ) from exc

    _jwks_cache["jwks"] = jwks
    _jwks_cache["expires_at"] = now + JWKS_TTL_SECONDS
    return jwks

def _get_signing_key(token: str) -> dict:
    try:
        unverified_header = jwt.get_unverified_header(token)
    except JWTError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid token header.",
        ) from exc

    kid = unverified_header.get("kid")
    if not kid:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Token header is missing kid.",
        )

    for force_refresh in (False, True):
        jwks = _load_jwks(force_refresh=force_refresh)
        keys = jwks.get("keys", [])
        key = next((item for item in keys if item.get("kid") == kid), None)
        if key:
            return key

    raise HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail="No matching signing key.",
    )

def _require_cognito_setting(value: str | None, *, field_name: str) -> str:
    if not value:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Missing Cognito configuration: {field_name}.",
        )
    return value

def validate_cognito_id_token(token: str, access_token: str | None = None) -> AuthenticatedIdentity:
    if not token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing required token for Cognito auth",
        )
    key = _get_signing_key(token)

    try:
        claims = jwt.decode(
            token,
            key,
            algorithms=["RS256"],
            audience=_require_cognito_setting(
                settings.cognito_app_client_id,
                field_name="COGNITO_APP_CLIENT_ID",
            ),
            issuer=_require_cognito_setting(
                settings.cognito_issuer,
                field_name="COGNITO_ISSUER",
            ),
            access_token=access_token,
        )
    except JWTError as exc:
        logger.error("JWT decode failed: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=f"JWT decode failed: {exc}",
        )
    
    token_use = claims.get("token_use")
    if token_use != "id":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Expected a Cognito ID token.",
        )
    subject = claims.get("sub")
    email = claims.get("email")
    if not subject or not email:
       raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing valid subject or email for Cognito auth",
        )

    display_name = claims.get("name")
    if not display_name:
        given = claims.get("given_name", "")
        family = claims.get("family_name", "")
        display_name = f"{given} {family}".strip() or email
    groups = _normalize_cognito_groups(claims.get("cognito:groups"))

    return AuthenticatedIdentity(
        subject=subject,
        email=email,
        display_name=display_name,
        groups=groups,
    )


def validate_cognito_access_token(token: str) -> AuthenticatedIdentity:
    if not token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing required token for Cognito auth",
        )

    key = _get_signing_key(token)
    try:
        claims = jwt.decode(
            token,
            key,
            algorithms=["RS256"],
            issuer=_require_cognito_setting(
                settings.cognito_issuer,
                field_name="COGNITO_ISSUER",
            ),
            options={"verify_aud": False},
        )
    except JWTError as exc:
        logger.error("Access token decode failed: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="JWT decode failed.",
        ) from exc

    token_use = claims.get("token_use")
    if token_use != "access":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Expected a Cognito access token.",
        )

    client_id = claims.get("client_id")
    if client_id != _require_cognito_setting(
        settings.cognito_app_client_id,
        field_name="COGNITO_APP_CLIENT_ID",
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Access token client id did not match this app client.",
        )

    subject = claims.get("sub")
    if not subject:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Access token missing subject.",
        )
    groups = _normalize_cognito_groups(claims.get("cognito:groups"))

    return AuthenticatedIdentity(subject=subject, groups=groups)


def exchange_auth_code_for_tokens(code: str) -> dict:
    if not code:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Missing authorization code.",
        )

    if not settings.cognito_app_client_id or not settings.cognito_redirect_uri:
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="Cognito client configuration is incomplete.",
        )

    form_data = {
        "grant_type": "authorization_code",
        "client_id": settings.cognito_app_client_id,
        "code": code,
        "redirect_uri": settings.cognito_redirect_uri,
    }
    headers = {"Content-Type": "application/x-www-form-urlencoded"}

    if settings.cognito_app_client_secret:
        basic_auth = f"{settings.cognito_app_client_id}:{settings.cognito_app_client_secret}"
        encoded = base64.b64encode(basic_auth.encode("utf-8")).decode("utf-8")
        headers["Authorization"] = f"Basic {encoded}"

    try:
        response = httpx.post(
            _token_endpoint_url(),
            data=form_data,
            headers=headers,
            timeout=15.0,
        )
        response.raise_for_status()
        payload = response.json()
    except httpx.HTTPStatusError as exc:
        detail = exc.response.text
        logger.error("Cognito token exchange failed: %s", detail)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Could not exchange Cognito authorization code.",
        ) from exc
    except (httpx.HTTPError, json.JSONDecodeError) as exc:
        logger.error("Cognito token exchange failed: %s", exc)
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Cognito token exchange failed.",
        ) from exc

    if "id_token" not in payload or "access_token" not in payload:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Cognito token response was incomplete.",
        )

    return payload

def get_current_identity(authorization: str | None = Header(default=None)) -> AuthenticatedIdentity:
    token = _extract_bearer_token(authorization)
    return validate_cognito_access_token(token)
