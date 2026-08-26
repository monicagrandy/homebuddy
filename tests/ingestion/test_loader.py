import io

import httpx
import pytest
from pypdf import PdfWriter

from backend.ingestion.loader import DocumentLoadError, PDFDocumentLoader


def make_pdf_bytes() -> bytes:
    output = io.BytesIO()
    writer = PdfWriter()
    writer.add_blank_page(width=612, height=792)
    writer.write(output)
    return output.getvalue()


def test_remote_loader_sends_pdf_headers_and_extracts_pages():
    pdf_bytes = make_pdf_bytes()

    def handler(request: httpx.Request) -> httpx.Response:
        assert "application/pdf" in request.headers["accept"]
        assert "Mozilla/5.0" in request.headers["user-agent"]
        return httpx.Response(
            200,
            headers={"content-type": "application/pdf"},
            content=pdf_bytes,
        )

    client = httpx.Client(transport=httpx.MockTransport(handler))
    loader = PDFDocumentLoader(http_client=client)

    assert loader.load_pages_from_url("https://example.com/manual.pdf") == [""]


def test_remote_loader_retries_a_transient_timeout():
    pdf_bytes = make_pdf_bytes()
    attempts = 0
    delays: list[float] = []

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        if attempts == 1:
            raise httpx.ReadTimeout("slow origin", request=request)
        return httpx.Response(200, content=pdf_bytes)

    client = httpx.Client(transport=httpx.MockTransport(handler))
    loader = PDFDocumentLoader(
        http_client=client,
        download_attempts=2,
        sleep=delays.append,
    )

    assert loader.load_pages_from_url("https://example.com/slow.pdf") == [""]
    assert attempts == 2
    assert delays == [0.5]


def test_remote_loader_reports_timeout_after_retries_are_exhausted():
    attempts = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        raise httpx.ReadTimeout("still slow", request=request)

    client = httpx.Client(transport=httpx.MockTransport(handler))
    loader = PDFDocumentLoader(
        http_client=client,
        download_attempts=2,
        sleep=lambda _delay: None,
    )

    with pytest.raises(DocumentLoadError, match="timed out after 2 attempts"):
        loader.load_pages_from_url("https://example.com/slow.pdf")

    assert attempts == 2


def test_remote_loader_rejects_non_pdf_responses():
    client = httpx.Client(
        transport=httpx.MockTransport(
            lambda _request: httpx.Response(
                200,
                headers={"content-type": "text/html"},
                text="<html>Access denied</html>",
            )
        )
    )
    loader = PDFDocumentLoader(http_client=client)

    with pytest.raises(DocumentLoadError, match="did not return a PDF"):
        loader.load_pages_from_url("https://example.com/not-a-pdf")


def test_remote_loader_enforces_the_download_size_limit():
    client = httpx.Client(
        transport=httpx.MockTransport(
            lambda _request: httpx.Response(
                200,
                headers={"content-length": "2048"},
                content=b"%PDF-1.7",
            )
        )
    )
    loader = PDFDocumentLoader(http_client=client, max_pdf_bytes=1024)

    with pytest.raises(DocumentLoadError, match="too large"):
        loader.load_pages_from_url("https://example.com/large.pdf")
