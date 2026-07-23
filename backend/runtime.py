from functools import lru_cache

from backend.config import settings
from backend.guardrails.guardrails import SafetyGuardrail
from backend.services.hazard_assessment_service import HazardAssessmentService
from backend.services.routing_service import RoutingService
from langchain_openai import ChatOpenAI
from rag.query_engine import AgenticQueryEngine
from rag.search_agent import SearchAgent
from rag.vector_store import VectorStore, build_vector_store

@lru_cache 
def get_llm() -> ChatOpenAI:
    return settings.llm

@lru_cache 
def get_judge_llm() -> ChatOpenAI:
    return settings.judge_llm

@lru_cache
def get_vector_store() -> VectorStore:
    return build_vector_store()

@lru_cache
def get_query_engine() -> AgenticQueryEngine:
    llm_client = get_llm()
    vector_manager = get_vector_store()
    search_agent = SearchAgent(llm_client)
    return AgenticQueryEngine(
        vector_manager=vector_manager,
        search_agent=search_agent,
        llm_client=llm_client,
        multihop=False,
    )

@lru_cache
def get_safety_input_guardrail() -> SafetyGuardrail:
    return SafetyGuardrail()

@lru_cache
def get_safety_output_guardrail() -> SafetyGuardrail:
    return SafetyGuardrail()


@lru_cache
def get_hazard_assessment_service() -> HazardAssessmentService:
    return HazardAssessmentService()


@lru_cache
def get_routing_service() -> RoutingService:
    return RoutingService()
