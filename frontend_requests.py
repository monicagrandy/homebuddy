import asyncio
import contextlib
from collections.abc import Callable

import httpx
from streamlit import runtime
from streamlit.runtime.scriptrunner import get_script_run_ctx

SESSION_CHECK_INTERVAL_SECONDS = 0.25


class RequestAbandoned(Exception):
    """The browser session ended while a request was still in flight."""


def browser_session_is_active() -> bool:
    """False once the tab that started this script run has refreshed or closed."""
    ctx = get_script_run_ctx()
    if ctx is None or not runtime.exists():
        return True
    return runtime.get_instance().is_active_session(ctx.session_id)


async def post_while_active(
    client: httpx.AsyncClient,
    url: str,
    *,
    is_active: Callable[[], bool] = browser_session_is_active,
    poll_seconds: float = SESSION_CHECK_INTERVAL_SECONDS,
    **kwargs,
) -> httpx.Response:
    """POST and wait for the response, hanging up as soon as `is_active()` turns false.

    Hanging up closes the connection, which is how the backend learns to stop
    work nobody is waiting for. A blocking httpx call cannot be interrupted from
    the script thread, so callers run this on a short-lived event loop.
    """
    request = asyncio.ensure_future(client.post(url, **kwargs))
    while True:
        done, _ = await asyncio.wait({request}, timeout=poll_seconds)
        if done:
            return request.result()
        if not is_active():
            request.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await request
            raise RequestAbandoned()
