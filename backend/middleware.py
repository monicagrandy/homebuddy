"""ASGI middleware for the public API.

Written as plain ASGI rather than Starlette's BaseHTTPMiddleware, which wraps
responses and gets in the way of streaming and disconnect detection.
"""

from starlette.datastructures import Headers
from starlette.responses import PlainTextResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from backend.config import settings


def host_is_allowed(host: str, allowed_hosts: tuple[str, ...]) -> bool:
    if not allowed_hosts or "*" in allowed_hosts:
        return True
    host = host.lower()
    for pattern in allowed_hosts:
        pattern = pattern.lower()
        if pattern.startswith("*.") and host.endswith(pattern[1:]):
            return True
        if host == pattern:
            return True
    return False


class TrustedHostGuard:
    """Rejects requests whose Host header isn't in settings.allowed_hosts (empty allows all)."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] in ("http", "websocket"):
            host = Headers(scope=scope).get("host", "").rsplit(":", 1)[0]
            if not host_is_allowed(host, settings.allowed_hosts):
                response = PlainTextResponse("Invalid host header", status_code=400)
                await response(scope, receive, send)
                return
        await self.app(scope, receive, send)
