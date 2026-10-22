"""Per-user rate limits for the expensive routes (AI questions, document indexing).

Buckets live in process memory, which is correct while the backend runs as a
single uvicorn worker. With more workers each process would enforce its own
limit; move the buckets to Postgres or Redis before scaling out.
"""

import math
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass

from fastapi import Depends, HTTPException, status

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
