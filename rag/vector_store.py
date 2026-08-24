from __future__ import annotations

from abc import ABC, abstractmethod
from pathlib import Path
from typing import Any

try:
    import chromadb
except Exception: 
    chromadb = None
from langchain_openai import OpenAIEmbeddings
from sqlalchemy import delete, select

from backend.config import get_logger, settings
from backend.db import SessionLocal
from backend.models import DocumentChunk, Vector

logger = get_logger(__name__)


class VectorStore(ABC):
    @abstractmethod
    def upsert_chunks(
        self,
        *,
        household_id: int,
        entry_id: str,
        session_id: str,
        doc_type: str,
        chunks: list[dict[str, Any]],
    ) -> dict:
        """Persist indexed chunks for later retrieval."""

    @abstractmethod
    def query_chunks(
        self,
        *,
        household_id: int,
        query: str,
        session_id: str,
        doc_type: str | None,
        entry_id: str | None = None,
        where: dict | None = None,
        n_results: int = 3,
    ) -> list[dict[str, Any]]:
        """Return retrieval matches in a provider-agnostic shape."""

    @abstractmethod
    def delete_entry(
        self,
        *,
        household_id: int,
        entry_id: str,
        session_id: str | None = None,
    ) -> int:
        """Delete chunks for a single logical document entry."""


class ChromaVectorStore(VectorStore):
    def __init__(self, db_dir: Path):
        self.db_dir = db_dir
        if chromadb is None:
            raise RuntimeError(
                "Chroma is not installed. Use VECTOR_STORE_PROVIDER=pgvector for the primary persistence path."
            )
        logger.warning(
            "Using legacy Chroma vector store fallback. pgvector is the default and recommended provider."
        )
        try:
            self.client = chromadb.PersistentClient(path=str(db_dir))
        except Exception as exc:
            logger.critical("Failed to initialize Chroma DB at %s: %s", db_dir, exc)
            raise RuntimeError(f"Failed to connect to the vector database. Details: {exc}") from exc

    def get_collection(self, session_id: str = "default"):
        try:
            return self.client.get_or_create_collection(name=f"home_buddy_{session_id}")
        except Exception as exc:
            logger.error("Error accessing collection for session '%s': %s", session_id, exc)
            raise RuntimeError(f"Unable to access the latest text database session. Details {exc}") from exc

    def reset_collection(self, session_id: str = "default") -> None:
        try:
            self.client.delete_collection(name=f"home_buddy_{session_id}")
        except Exception as exc:
            logger.warning("Error deleting collection 'home_buddy_%s': %s", session_id, exc)

    def upsert_chunks(
        self,
        *,
        household_id: int,
        entry_id: str,
        session_id: str,
        doc_type: str,
        chunks: list[dict[str, Any]],
    ) -> dict:
        collection = self.get_collection(session_id)
        collection.upsert(
            ids=[f"{entry_id}-{household_id}-{i}" for i in range(len(chunks))],
            documents=[chunk["text"] for chunk in chunks],
            metadatas=[
                {
                    "entry_id": entry_id,
                    "household_id": household_id,
                    "source": chunk["source"],
                    "page": chunk["page"],
                    "doc_type": doc_type,
                }
                for chunk in chunks
            ],
        )
        return {
            "entry_id": entry_id,
            "household_id": household_id,
            "doc_type": doc_type,
            "chunks_indexed": len(chunks),
        }

    def query_chunks(
        self,
        *,
        household_id: int,
        query: str,
        session_id: str,
        doc_type: str | None,
        entry_id: str | None = None,
        where: dict | None = None,
        n_results: int = 3,
    ) -> list[dict[str, Any]]:
        collection = self.get_collection(session_id)
        query_kwargs: dict[str, Any] = {"query_texts": [query], "n_results": n_results}
        filters: list[dict[str, Any]] = [{"household_id": household_id}]
        if doc_type is not None:
            filters.append({"doc_type": doc_type})
        if entry_id:
            filters.append({"entry_id": entry_id})
        if where:
            filters.append(where)
        if len(filters) == 1:
            query_kwargs["where"] = filters[0]
        else:
            query_kwargs["where"] = {"$and": filters}

        results = collection.query(**query_kwargs)
        if not results or not results.get("documents") or not results["documents"][0]:
            return []

        matches = []
        for doc, meta in zip(results["documents"][0], results["metadatas"][0]):
            matches.append({"text": doc, "metadata": meta})
        return matches

    def delete_entry(
        self,
        *,
        household_id: int,
        entry_id: str,
        session_id: str | None = None,
    ) -> int:
        if session_id is not None:
            collection = self.get_collection(session_id)
            collection.delete(where={"$and": [{"household_id": household_id}, {"entry_id": entry_id}]})
            return 0
        raise RuntimeError(
            "Chroma delete_entry requires session_id. Refusing silent no-op deletion for legacy Chroma collections."
        )


class PgVectorStore(VectorStore):
    """Primary durable vector store for local and deployed environments."""

    def __init__(self, session_factory=SessionLocal):
        # Without the pgvector package, DocumentChunk.embedding falls back to a Text
        # column: inserts would fail binding a Python list and cosine_distance doesn't
        # exist. Refuse to construct rather than fail confusingly on first use.
        if Vector is None:
            raise RuntimeError(
                "PgVectorStore requires the 'pgvector' package. Install it, or set "
                "VECTOR_STORE_PROVIDER=chroma for the legacy fallback."
            )
        self.session_factory = session_factory
        self.embeddings = OpenAIEmbeddings(
            model="text-embedding-3-small",
            api_key=settings.openai_key,
        )

    def upsert_chunks(
        self,
        *,
        household_id: int,
        entry_id: str,
        session_id: str,
        doc_type: str,
        chunks: list[dict[str, Any]],
    ) -> dict:
        with self.session_factory() as session:
            session.execute(
                delete(DocumentChunk).where(
                    DocumentChunk.household_id == household_id,
                    DocumentChunk.entry_id == entry_id,
                )
            )
            texts = [chunk["text"] for chunk in chunks]
            vectors = self.embeddings.embed_documents(texts)    
            for index, (chunk, embedding) in enumerate(zip(chunks, vectors)):
                session.add(
                    DocumentChunk(
                        household_id=household_id,
                        entry_id=entry_id,
                        session_id=session_id,
                        doc_type=doc_type,
                        chunk_index=index,
                        source=chunk["source"],
                        page=chunk["page"],
                        text=chunk["text"],
                        embedding=embedding
                    )
                )
            session.commit()

        return {
            "entry_id": entry_id,
            "household_id": household_id,
            "doc_type": doc_type,
            "chunks_indexed": len(chunks),
        }

    def query_chunks(
        self,
        *,
        household_id: int,
        query: str,
        session_id: str,
        doc_type: str | None,
        entry_id: str | None = None,
        where: dict | None = None,
        n_results: int = 3,
    ) -> list[dict[str, Any]]:
        if where:
            raise NotImplementedError(
                "PgVectorStore does not yet support the generic 'where' filter."
            )
        query_embedding = self.embeddings.embed_query(query)
        with self.session_factory() as session:
            stmt = select(DocumentChunk).where(
                DocumentChunk.household_id == household_id,
            )
            if doc_type is not None:
                stmt = stmt.where(DocumentChunk.doc_type == doc_type)
            if entry_id:
                stmt = stmt.where(DocumentChunk.entry_id == entry_id)
            
            stmt = stmt.order_by(
                DocumentChunk.embedding.cosine_distance(query_embedding)).limit(n_results)
            rows = session.scalars(stmt).all()

   
        return [
            {
                "text": row.text,
                "metadata": {
                    "entry_id": row.entry_id,
                    "household_id": row.household_id,
                    "source": row.source,
                    "page": row.page,
                    "doc_type": row.doc_type,
                },
            }
            for row in rows
        ]

    def delete_entry(
        self,
        *,
        household_id: int,
        entry_id: str,
        session_id: str | None = None,
    ) -> int:
        with self.session_factory() as session:
            stmt = delete(DocumentChunk).where(
                DocumentChunk.household_id == household_id,
                DocumentChunk.entry_id == entry_id,
            )
            result = session.execute(stmt)
            session.commit()
            return result.rowcount or 0


def build_vector_store() -> VectorStore:
    provider = settings.vector_store_provider
    if provider == "chroma":
        return ChromaVectorStore(db_dir=Path(settings.chroma_db_dir))
    if provider != "pgvector":
        raise RuntimeError(
            f"Unsupported VECTOR_STORE_PROVIDER={provider!r}. Use 'pgvector' or 'chroma'."
        )
    if not settings.database_url.startswith("postgresql"):
        raise RuntimeError("VECTOR_STORE_PROVIDER=pgvector requires a PostgreSQL DATABASE_URL")
    return PgVectorStore()
