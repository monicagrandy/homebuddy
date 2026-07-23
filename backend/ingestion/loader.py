import io
import logging

import httpx
from pypdf import PdfReader
from pypdf.errors import PdfReadError

logger = logging.getLogger(__name__)

# --- Custom Exception Boundaries ---
class DocumentLoadError(Exception):
    """Raised when fetching or reading a document fails"""
    pass

class PDFDocumentLoader:
    def __init__(self, http_client: httpx.Client = None):
        """Injects an optional HTTP client to manage connection pools/configurations."""
        self.client = http_client or httpx.Client(timeout=15.0, follow_redirects=True)

    def load_pages_from_url(self, url: str) -> list[str]:
        """Downloads a PDF from a remote web URL and extracts page text."""
        try:
            # Fetch raw PDF file stream using the injected client
            resp = self.client.get(url)
            resp.raise_for_status()

            # Read and extract text
            reader = PdfReader(io.BytesIO(resp.content))
            return [page.extract_text() or "" for page in reader.pages]
        except httpx.HTTPStatusError as e:
            raise DocumentLoadError(f"Download failed with status code:{e.response.status_code}")
        except httpx.RequestError as e:
            raise DocumentLoadError(f"Network connection error: Failed to connect to server: {e}")
        except PdfReadError as e:
            raise DocumentLoadError(f"The downloaded file is not a valid PDF or is corrupted: {e}")
        except Exception as e:
            raise DocumentLoadError(f"An unexpected error occured while downloading: {e}")
    
    def load_pages_from_upload(self, file_bytes:bytes) -> list[str]:
        """Reads uploaded PDF bytes and extracts text."""
        try:
            reader = PdfReader(io.BytesIO(file_bytes))
            return [page.extract_text() or "" for page in reader.pages]
        except PdfReadError as e:
            raise DocumentLoadError(f"The uploaded file is corrupted or not a valid PDF: {e}")
        except Exception as e:
            raise DocumentLoadError(f"Failed to read the uploaded document: {e}")
    
    
