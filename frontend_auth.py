import base64
import binascii
import json
import time
from collections.abc import MutableMapping


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


def jwt_is_expired(token: str | None, *, now: float | None = None) -> bool:
    """Treat malformed JWTs as expired; the backend still performs full validation."""
    expiration = jwt_expiration(token)
    if expiration is None:
        return True
    return expiration <= (time.time() if now is None else now)


def claim_auth_code(state: MutableMapping, code: str | None) -> bool:
    """Atomically mark a one-time OAuth code before any exchange can trigger a rerun."""
    if not code or state.get("processed_auth_code") == code:
        return False
    state["processed_auth_code"] = code
    return True
