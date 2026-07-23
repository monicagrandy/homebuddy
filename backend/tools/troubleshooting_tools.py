from __future__ import annotations

from langchain_core.tools import tool

from backend.config import get_logger, settings
from rag.llm_clients import OpenAIClient
from rag.search_agent import SearchAgent

logger = get_logger(__name__)
llm_client = OpenAIClient(model_name=settings.openai_model)

@tool
def search_web(query: str) -> str:
    """Search the web for troubleshooting guidance when saved manuals are insufficient"""
    search_agent = SearchAgent(llm_client)
    return search_agent.execute_synthesized_search(query)
