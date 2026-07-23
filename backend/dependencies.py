from functools import lru_cache

from httpx import Client

from backend.ingestion.loader import PDFDocumentLoader
from backend.ingestion.pipeline import IngestionPipeline
from backend.runtime import get_vector_store
from backend.services.ingestion_service import IngestionService
from backend.services.query_service import QueryService
from backend.workflow.graph import build_graph

@lru_cache
def get_state_graph():
    return build_graph()

@lru_cache
def get_query_service() -> QueryService:
    return QueryService(get_state_graph())

@lru_cache
def get_pdf_loader() -> PDFDocumentLoader:
    return PDFDocumentLoader(http_client=Client(timeout=15.0, follow_redirects=True))

@lru_cache
def get_ingestion_service() -> IngestionService:
    vector_manager = get_vector_store()
    loader = get_pdf_loader()
    pipeline = IngestionPipeline(vector_manager=vector_manager, loader=loader)
    return IngestionService(vector_manager=vector_manager, pipeline=pipeline)
