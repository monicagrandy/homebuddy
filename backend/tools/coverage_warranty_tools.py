from __future__ import annotations

from langchain_core.tools import tool

from backend.config import get_logger
from backend.runtime import get_query_engine

logger = get_logger(__name__)

def build_coverage_and_receipts_tool(
    *,
    household_id: int,
    session_id: str,
    entry_id: str | None,
):

    @tool
    def search_coverage_and_receipts(
        query: str,
    ) -> str:
        """Search the coverage/warranty/receipt docs that have been saved to a household.

        Args:
            query: natural-language search e.g "Is my fridge still under warranty?"
        """
        logger.info("search_search_coverage_and_receipts_docs query=%r", query)
        engine = get_query_engine()
        return engine.local_vector_search(
            household_id=household_id,
            query=query,
            session_id=session_id,
            entry_id=entry_id,
            doc_type="warranty"
        )
    
    return search_coverage_and_receipts
