import base64
import binascii
import json
import time
from collections.abc import Mapping, MutableMapping


def jwt_expiration(token: str | None) -> int | None:
    """Read a JWT expiry without treating the unverified payload as authentication."""
    if not token:
        return None

    try:
        payload_segment = token.split(".")[1]
        padding = "=" * (-len(payload_segment) % 4)
        payload = json.loads(base64.urlsafe_b64decode(payload_segment + padding))
        expiration = payload.get("exp")
        return int(expiration) if expiration is not None else None
    except (IndexError, TypeError, ValueError, UnicodeError, binascii.Error):
        return None


def jwt_is_expired(
    token: str | None,
    *,
    now: float | None = None,
    leeway: float = 0,
) -> bool:
    """Treat malformed JWTs as expired; the backend still performs full validation.

    A positive leeway reports tokens that expire within that many seconds.
    """
    expiration = jwt_expiration(token)
    if expiration is None:
        return True
    return expiration <= (time.time() if now is None else now) + leeway


def access_token_needs_renewal(
    state: Mapping,
    *,
    leeway: float,
    now: float | None = None,
) -> bool:
    """Renew only while signed in with a refresh token; without one the session ends at expiry."""
    if not state.get("auth_token") or not state.get("refresh_token"):
        return False
    return jwt_is_expired(state["auth_token"], now=now, leeway=leeway)


def store_renewed_tokens(state: MutableMapping, tokens: Mapping) -> None:
    state["auth_token"] = tokens["access_token"]
    state["access_token"] = tokens["access_token"]
    state["id_token"] = tokens["id_token"]
    # Cognito only returns a new refresh token when rotation is enabled.
    if tokens.get("refresh_token"):
        state["refresh_token"] = tokens["refresh_token"]
    # Rewrite the browser copy of the access token so a page refresh restores
    # the new one. "sync" writes without replying: a reply would rerun the app
    # and could cut off an upload or chat answer in progress. A pending
    # sign-in "write" reads the new token when it runs, so it is left alone.
    if state.get("auth_storage_action") in (None, "read"):
        state["auth_storage_action"] = "sync"


def claim_auth_code(state: MutableMapping, code: str | None) -> bool:
    """Atomically mark a one-time OAuth code before any exchange can trigger a rerun."""
    if not code or state.get("processed_auth_code") == code:
        return False
    state["processed_auth_code"] = code
    return True
