from functools import lru_cache
from time import perf_counter

from httpx import Client

from backend.config import get_logger
from backend.ingestion.loader import PDFDocumentLoader
from backend.ingestion.pipeline import IngestionPipeline
from backend.runtime import get_query_engine, get_routing_service, get_safety_guardrail, get_vector_store
from backend.services.ingestion_service import IngestionService
from backend.services.query_service import QueryService
from backend.workflow.graph import build_graph

logger = get_logger(__name__)


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


def warm_runtime_components() -> None:
    from backend.agents.coverage_warranty import get_coverage_warranty_subgraph
    from backend.agents.operations import get_operations_subgraph
    from backend.agents.troubleshooting import get_troubleshooting_subgraph

    warm_steps = [
        ("routing service", get_routing_service),
        ("state graph", get_state_graph),
        ("query service", get_query_service),
        ("vector store", get_vector_store),
        ("query engine", get_query_engine),
        ("operations subgraph", get_operations_subgraph),
        ("troubleshooting subgraph", get_troubleshooting_subgraph),
        ("coverage subgraph", get_coverage_warranty_subgraph),
    ]

    guardrail = get_safety_guardrail()
    t0 = perf_counter()
    guardrail.warm_up()
    logger.info("Warmup complete: Presidio guardrail loaded in %.2fs", perf_counter() - t0)

    for label, loader in warm_steps:
        t0 = perf_counter()
        loader()
        logger.info("Warmup complete: %s loaded in %.2fs", label, perf_counter() - t0)
