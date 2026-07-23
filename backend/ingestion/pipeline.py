from backend.config import get_logger
from backend.ingestion.loader import DocumentLoadError, PDFDocumentLoader
from rag.vector_store import VectorStore

logger = get_logger(__name__)


class IngestionPipeline:
    def __init__(
        self,
        loader: PDFDocumentLoader,
        vector_manager: VectorStore,
        chunk_size: int = 1500,
        overlap: int = 200,
    ):
        self.loader = loader
        self.vector_manager = vector_manager
        self.chunk_size = chunk_size
        self.overlap = overlap

    @staticmethod
    def recursive_split(
        text: str,
        max_size: int = 1500,
        separators: list[str] | None = None,
    ) -> list[str]:
        if separators is None:
            separators = ["\n\n", "\n", ". ", " "]

        if len(text) <= max_size:
            return [text]

        for sep in separators:
            if sep not in text:
                continue
            parts = text.split(sep)
            chunks = []
            current_chunk = ""
            for part in parts:
                if len(current_chunk) + len(sep) + len(part) > max_size:
                    if current_chunk:
                        chunks.append(current_chunk.strip())
                    current_chunk = part
                else:
                    current_chunk += (sep if current_chunk else "") + part
            if current_chunk:
                chunks.append(current_chunk.strip())
            if chunks:
                return chunks

        return [text[i : i + max_size] for i in range(0, len(text), max_size)]

    def ingest_upload_from_file(
        self,
        household_id: int,
        entry_id: str,
        file_bytes: bytes,
        doc_type: str,
        session_id: str = "default",
    ) -> dict:
        try:
            pages = self.loader.load_pages_from_upload(file_bytes)
            chunks = []
            for page_idx, page_text in enumerate(pages):
                for chunk in self.recursive_split(page_text, max_size=self.chunk_size):
                    chunks.append(
                        {
                            "text": chunk,
                            "source": entry_id,
                            "page": page_idx + 1,
                            "doc_type": doc_type,
                        }
                    )

            return self.vector_manager.upsert_chunks(
                household_id=household_id,
                entry_id=entry_id,
                session_id=session_id,
                doc_type=doc_type,
                chunks=chunks,
            )
        except DocumentLoadError as exc:
            logger.error("Document failed to load for entry %r: %s", entry_id, exc)
            raise
        except Exception as exc:
            logger.error("Indexing pipeline failed for entry %r: %s", entry_id, exc)
            raise RuntimeError(f"Failed to add manual database index: {exc}") from exc

    def ingest_download_from_url(
        self,
        household_id: int,
        entry_id: str,
        url: str,
        doc_type: str,
        session_id: str = "default",  
    ) -> dict:
        try:
            pages = self.loader.load_pages_from_url(url)
            if not pages:
                raise DocumentLoadError("No text content could be extracted from this PDF.")

            chunks = []
            for page_idx, page_text in enumerate(pages):
                for chunk in self.recursive_split(page_text, max_size=self.chunk_size):
                    chunks.append(
                        {
                            "text": chunk,
                            "source": url,
                            "page": page_idx + 1,
                            "doc_type": doc_type,
                        }
                    )

            return self.vector_manager.upsert_chunks(
                household_id=household_id,
                entry_id=entry_id,
                session_id=session_id,
                doc_type=doc_type,
                chunks=chunks,
            )
        except DocumentLoadError as exc:
            logger.error("Document failed to load for entry %r: %s", entry_id, exc)
            raise
        except Exception as exc:
            logger.error("Indexing pipeline failed for entry %r: %s", entry_id, exc)
            raise RuntimeError(f"Failed to add manual database index: {exc}") from exc
