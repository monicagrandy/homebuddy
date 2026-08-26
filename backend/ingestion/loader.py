import io
import logging
import time
from collections.abc import Callable

import httpx
from pypdf import PdfReader
from pypdf.errors import PdfReadError

logger = logging.getLogger(__name__)

PDF_REQUEST_HEADERS = {
    "Accept": "application/pdf, application/octet-stream;q=0.9, */*;q=0.8",
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/126.0 Safari/537.36 HomeBuddy/1.0"
    ),
}
PDF_DOWNLOAD_TIMEOUT = httpx.Timeout(connect=10.0, read=45.0, write=10.0, pool=10.0)
MAX_PDF_BYTES = 50 * 1024 * 1024
RETRYABLE_STATUS_CODES = frozenset({408, 425, 429, 500, 502, 503, 504})


# --- Custom Exception Boundaries ---
class DocumentLoadError(Exception):
    """Raised when fetching or reading a document fails."""

    pass


class PDFDocumentLoader:
    def __init__(
        self,
        http_client: httpx.Client | None = None,
        *,
        max_pdf_bytes: int = MAX_PDF_BYTES,
        download_attempts: int = 2,
        retry_backoff_seconds: float = 0.5,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        """Injects an optional HTTP client to manage connection pools/configurations."""
        if download_attempts < 1:
            raise ValueError("download_attempts must be at least 1")
        if max_pdf_bytes < 1:
            raise ValueError("max_pdf_bytes must be positive")

        self.client = http_client or httpx.Client(
            timeout=PDF_DOWNLOAD_TIMEOUT,
            follow_redirects=True,
            headers=PDF_REQUEST_HEADERS,
        )
        self.max_pdf_bytes = max_pdf_bytes
        self.download_attempts = download_attempts
        self.retry_backoff_seconds = retry_backoff_seconds
        self.sleep = sleep

    def load_pages_from_url(self, url: str) -> list[str]:
        """Downloads a PDF from a remote web URL and extracts page text."""
        pdf_bytes = self._download_pdf(url)
        try:
            reader = PdfReader(io.BytesIO(pdf_bytes))
            return [page.extract_text() or "" for page in reader.pages]
        except PdfReadError as e:
            raise DocumentLoadError(
                f"The downloaded file is not a valid PDF or is corrupted: {e}"
            ) from e
        except Exception as e:
            raise DocumentLoadError(f"Failed to read the downloaded PDF: {e}") from e

    def _download_pdf(self, url: str) -> bytes:
        """Stream a bounded PDF download, retrying only transient failures."""
        for attempt in range(1, self.download_attempts + 1):
            try:
                return self._download_pdf_once(url)
            except httpx.HTTPStatusError as exc:
                status_code = exc.response.status_code
                if status_code in RETRYABLE_STATUS_CODES and attempt < self.download_attempts:
                    self._wait_before_retry(url, attempt, f"HTTP {status_code}")
                    continue
                raise DocumentLoadError(
                    f"PDF download failed with HTTP {status_code}."
                ) from exc
            except httpx.TimeoutException as exc:
                if attempt < self.download_attempts:
                    self._wait_before_retry(url, attempt, "a timeout")
                    continue
                raise DocumentLoadError(
                    "The PDF download timed out after "
                    f"{self.download_attempts} attempts. "
                    "Try again or upload the PDF directly."
                ) from exc
            except httpx.RequestError as exc:
                if attempt < self.download_attempts:
                    self._wait_before_retry(url, attempt, "a network error")
                    continue
                raise DocumentLoadError(
                    "The PDF could not be downloaded after "
                    f"{self.download_attempts} attempts: {exc}"
                ) from exc

        raise DocumentLoadError("The PDF could not be downloaded.")

    def _download_pdf_once(self, url: str) -> bytes:
        with self.client.stream("GET", url, headers=PDF_REQUEST_HEADERS) as response:
            response.raise_for_status()

            content_length = response.headers.get("content-length")
            if content_length:
                try:
                    declared_size = int(content_length)
                except ValueError:
                    declared_size = None
                if declared_size is not None and declared_size > self.max_pdf_bytes:
                    raise DocumentLoadError(
                        f"The PDF is too large. Maximum supported size is {self._max_size_label()}."
                    )

            content = bytearray()
            for chunk in response.iter_bytes():
                content.extend(chunk)
                if len(content) > self.max_pdf_bytes:
                    raise DocumentLoadError(
                        f"The PDF is too large. Maximum supported size is {self._max_size_label()}."
                    )

        pdf_bytes = bytes(content)
        if not pdf_bytes:
            raise DocumentLoadError("The PDF download returned an empty file.")
        if b"%PDF-" not in pdf_bytes[:1024]:
            content_type = response.headers.get("content-type", "unknown content type")
            raise DocumentLoadError(
                "The URL did not return a PDF "
                f"({content_type}). Check that it links directly to a PDF file."
            )
        return pdf_bytes

    def _wait_before_retry(self, url: str, attempt: int, reason: str) -> None:
        delay = self.retry_backoff_seconds * (2 ** (attempt - 1))
        logger.warning(
            "PDF download attempt %d/%d failed for %s due to %s; retrying in %.1fs",
            attempt,
            self.download_attempts,
            url,
            reason,
            delay,
        )
        self.sleep(delay)

    def _max_size_label(self) -> str:
        return f"{self.max_pdf_bytes // (1024 * 1024)} MB"

    def load_pages_from_upload(self, file_bytes: bytes) -> list[str]:
        """Reads uploaded PDF bytes and extracts text."""
        try:
            reader = PdfReader(io.BytesIO(file_bytes))
            return [page.extract_text() or "" for page in reader.pages]
        except PdfReadError as e:
            raise DocumentLoadError(
                f"The uploaded file is corrupted or not a valid PDF: {e}"
            ) from e
        except Exception as e:
            raise DocumentLoadError(f"Failed to read the uploaded document: {e}") from e
