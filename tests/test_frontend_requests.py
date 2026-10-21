import asyncio

import httpx
import pytest

from frontend_requests import RequestAbandoned, post_while_active


def _run(coro):
    return asyncio.run(coro)


def test_post_while_active_returns_the_response():
    async def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"chunks_indexed": 4})

    async def send():
        async with httpx.AsyncClient(
            base_url="http://backend", transport=httpx.MockTransport(handler)
        ) as client:
            return await post_while_active(client, "/documents/index", is_active=lambda: True)

    response = _run(send())

    assert response.json() == {"chunks_indexed": 4}


def test_post_while_active_hangs_up_once_the_session_ends():
    handler_cancelled = False
    checks = 0

    async def slow_handler(request: httpx.Request) -> httpx.Response:
        nonlocal handler_cancelled
        try:
            await asyncio.sleep(10)
        except asyncio.CancelledError:
            handler_cancelled = True
            raise
        return httpx.Response(200)

    def is_active() -> bool:
        nonlocal checks
        checks += 1
        return checks < 2

    async def send():
        async with httpx.AsyncClient(
            base_url="http://backend", transport=httpx.MockTransport(slow_handler)
        ) as client:
            return await post_while_active(
                client, "/documents/index", is_active=is_active, poll_seconds=0.01
            )

    with pytest.raises(RequestAbandoned):
        _run(send())

    assert handler_cancelled is True
