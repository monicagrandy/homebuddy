from __future__ import annotations

from functools import lru_cache
from typing import Literal

from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command

from backend.agents.prompts import COVERAGE_PROMPT
from backend.agents.state import AgentState, build_context
from backend.config import get_logger
from backend.runtime import get_query_engine, get_llm
from backend.workflow.state import WorkerInput

logger = get_logger(__name__)

def build_coverage_warranty_subgraph(agent_llm):
    def coverage_and_warranty_model(state: AgentState) -> dict:
        response = agent_llm.invoke(state["messages"])
        return {"messages": [response]}
    
    cg = StateGraph(AgentState)
    cg.add_node("model", coverage_and_warranty_model)
    cg.add_edge(START, "model")
    return cg.compile()

@lru_cache
def get_coverage_warranty_subgraph():
    return build_coverage_warranty_subgraph(get_llm())

def _format_retrieval_matches(matches: list[dict]) -> str:
    if not matches:
        return "No relevant coverage or warranty passages were found."
    parts = []
    for index, match in enumerate(matches, start=1):
        meta = match["metadata"]
        parts.append(
            "\n".join(
                [
                    f"--- Retrieved Chunk {index} ---",
                    f"Source: {meta.get('source', 'unknown')}",
                    f"Page: {meta.get('page', 'n/a')}",
                    f"Entry ID: {meta.get('entry_id', 'unknown')}",
                    f"Doc Type: {meta.get('doc_type', 'unknown')}",
                    "Content:",
                    match["text"],
                ]
            )
        )
    return "\n\n".join(parts)


def _coverage_and_warranty_agent_node(state: WorkerInput) -> Command[Literal["synthesizer"]]:
    """Deterministically fetch relevant coverage docs before invoking the agent."""
    user_query = state.get("sanitized_query") or state["user_query"]
    task_desc = state.get("task_description", user_query)
    context = build_context(state.get("messages", []))
    logger.info("Coverage and Warranty Agent task=%r", task_desc)

    engine = get_query_engine()
    local_matches = engine.retrieve_local_documents(
        household_id=state["household_id"],
        query=user_query,
        session_id=state["session_id"],
        entry_id=state.get("entry_id"),
        doc_type="warranty",
        n_results=3,
    )
    coverage_evidence = _format_retrieval_matches(local_matches)

    result = get_coverage_warranty_subgraph().invoke({"messages": [
        SystemMessage(content=COVERAGE_PROMPT),
        HumanMessage(
            content=(
                f"{context}Task: {task_desc}\n"
                f"Customer query: {user_query}\n"
                f"Retrieval Context: {coverage_evidence}"
            )
        ),
    ]})
    answer = result["messages"][-1].content

    return Command(
        update={
            "coverage_response": [{"agent": "coverage_and_warranty_agent", "retrieval_context": [coverage_evidence], "response": answer}],
            "retrieval_context": [coverage_evidence],
        },
        goto="synthesizer",
    )
    
coverage_and_warranty_agent = _coverage_and_warranty_agent_node
