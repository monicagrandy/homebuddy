"""Rate limits for the expensive routes: per user for the signed-in API, per visitor for the public demo.

Buckets live in process memory, which is correct while the backend runs as a
single uvicorn worker. With more workers each process would enforce its own
limit; move the buckets to Postgres or Redis before scaling out.
"""

import ipaddress
import math
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from datetime import date, datetime, timezone

from fastapi import Depends, HTTPException, Request, status

from backend.auth import AuthenticatedIdentity, get_current_identity
from backend.config import settings


@dataclass
class _Bucket:
    tokens: float
    updated_at: float


class RateLimiter:
    """Token buckets keyed by (bucket name, user). Each refills at per_minute / 60 tokens per second."""

    def __init__(self, clock: Callable[[], float] = time.monotonic) -> None:
        self._clock = clock
        self._buckets: dict[tuple[str, str], _Bucket] = {}
        self._lock = threading.Lock()

    def check(self, bucket: str, key: str, *, per_minute: int) -> float | None:
        """Take one token. Returns None when allowed, else seconds until a token is available."""
        if per_minute <= 0:
            return None

        capacity = float(per_minute)
        refill_per_second = per_minute / 60.0
        now = self._clock()
        with self._lock:
            state = self._buckets.get((bucket, key))
            if state is None:
                state = _Bucket(tokens=capacity, updated_at=now)
                self._buckets[(bucket, key)] = state
            state.tokens = min(capacity, state.tokens + (now - state.updated_at) * refill_per_second)
            state.updated_at = now
            if state.tokens >= 1:
                state.tokens -= 1
                return None
            return (1 - state.tokens) / refill_per_second

    def reset(self) -> None:
        with self._lock:
            self._buckets.clear()


limiter = RateLimiter()

_LIMITS: dict[str, Callable[[], int]] = {
    "query": lambda: settings.rate_limit_queries_per_minute,
    "documents": lambda: settings.rate_limit_documents_per_minute,
}


def rate_limited(bucket: str):
    """FastAPI dependency that spends one token from `bucket` for the signed-in user."""
    limit_for = _LIMITS[bucket]

    def enforce(identity: AuthenticatedIdentity = Depends(get_current_identity)) -> None:
        retry_after = limiter.check(bucket, identity.subject, per_minute=limit_for())
        if retry_after is not None:
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail="Too many requests. Please wait a moment and try again.",
                headers={"Retry-After": str(max(1, math.ceil(retry_after)))},
            )

    return enforce


def _utc_today() -> date:
    return datetime.now(timezone.utc).date()


class DailyQuota:
    """Counts uses per key, starting again at each UTC date."""

    def __init__(self, today: Callable[[], date] = _utc_today) -> None:
        self._today = today
        self._day: date | None = None
        self._counts: dict[str, int] = {}
        self._lock = threading.Lock()

    def take(self, key: str, *, limit: int) -> bool:
        """Count one use. Returns False, without counting it, once the key has used its limit today."""
        if limit <= 0:
            return True
        with self._lock:
            today = self._today()
            if today != self._day:
                self._day = today
                self._counts.clear()
            used = self._counts.get(key, 0)
            if used >= limit:
                return False
            self._counts[key] = used + 1
            return True

    def reset(self) -> None:
        with self._lock:
            self._day = None
            self._counts.clear()


demo_quota = DailyQuota()

_DEMO_DAILY_LIMIT_MESSAGE = (
    "The demo has answered as many questions as it can today. Please come back tomorrow."
)


def visitor_key(request: Request) -> str:
    """The visitor's IP (Caddy forwards it); IPv6 addresses are grouped by their /64 network."""
    host = request.client.host if request.client else "unknown"
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return host
    if address.version == 6:
        return str(ipaddress.ip_network(f"{address}/64", strict=False))
    return str(address)


def demo_rate_limited(request: Request) -> None:
    """FastAPI dependency for the public demo: per-visitor minute and day limits, then a daily total."""
    key = visitor_key(request)
    retry_after = limiter.check("demo", key, per_minute=settings.demo_queries_per_minute)
    if retry_after is not None:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="That's a lot of questions at once. Please wait a moment and try again.",
            headers={"Retry-After": str(max(1, math.ceil(retry_after)))},
        )
    if not demo_quota.take(f"visitor:{key}", limit=settings.demo_queries_per_visitor_per_day):
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=_DEMO_DAILY_LIMIT_MESSAGE)
    if not demo_quota.take("all", limit=settings.demo_queries_per_day):
        raise HTTPException(status_code=status.HTTP_429_TOO_MANY_REQUESTS, detail=_DEMO_DAILY_LIMIT_MESSAGE)
