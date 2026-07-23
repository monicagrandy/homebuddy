from backend.config import get_logger
from backend.ingestion.loader import DocumentLoadError
from backend.ingestion.pipeline import IngestionPipeline
from rag.vector_store import VectorStore

logger = get_logger(__name__)

class IngestionError(Exception):
    """Raised when indexing fails after request validation passes."""


class IngestionService:
    def __init__(
        self,
        vector_manager: VectorStore,
        pipeline: IngestionPipeline,
        chunk_size: int = 1500,
        overlap: int = 200,
    ) -> None:
        self.vector_manager = vector_manager
        self.chunk_size = chunk_size
        self.overlap = overlap
        self.pipeline = pipeline

    def index(
        self,
        household_id: int,
        entry_id: str,
        session_id: str,
        doc_type: str,
        url: str | None = None,
        file_bytes: bytes | None = None,
    ) -> dict:
        if not entry_id:
            raise ValueError("Missing entry ID.")
        if not household_id:
            raise ValueError("Missing household ID.")
        if not session_id:
            raise ValueError("Missing session ID.")
        if not url and not file_bytes:
            raise ValueError("Missing document content.")
        if url and file_bytes:
            raise ValueError("Provide either a URL or a file, not both.")

        try:
            if url:
                return self.pipeline.ingest_download_from_url(household_id, entry_id, url, doc_type, session_id)
            return self.pipeline.ingest_upload_from_file(household_id, entry_id, file_bytes, doc_type, session_id)
        except DocumentLoadError:
            raise
        except Exception as exc:
            logger.exception("Document indexing failed for entry_id=%s", entry_id)
            raise IngestionError("Failed to index document.") from exc
