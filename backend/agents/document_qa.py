from __future__ import annotations

from functools import lru_cache
from typing import Literal

from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command

from backend.agents.agent_flow import finalize_or_synthesize
from backend.agents.prompts import DOCUMENT_QA_PROMPT
from backend.agents.state import AgentState, build_context
from backend.config import get_logger
from backend.runtime import get_llm, get_query_engine
from backend.tools.troubleshooting_tools import search_web
from backend.workflow.state import WorkerInput

logger = get_logger(__name__)

GENERIC_TASK_PREFIXES = (
    "help the user troubleshoot",
    "assist with troubleshooting",
)


def build_document_qa_subgraph(agent_llm, tools_by_name):
    def document_qa_model(state: AgentState) -> dict:
        response = agent_llm.invoke(state["messages"])
        return {"messages": [response]}

    def document_qa_tools(state: AgentState) -> dict:
        last = state["messages"][-1]
        results = []
        for tc in last.tool_calls:
            name, args = tc["name"], tc["args"]
            out = tools_by_name[name].invoke(args) if name in tools_by_name else f"Unknown tool: {name}"
            results.append(ToolMessage(content=str(out), tool_call_id=tc["id"]))
        return {"messages": results}

    def should_continue(state: AgentState) -> str:
        last = state["messages"][-1]
        tool_calls = [m for m in state["messages"] if isinstance(m, ToolMessage)]
        if hasattr(last, "tool_calls") and last.tool_calls and len(tool_calls) < 5:
            return "tools"
        return END

    graph = StateGraph(AgentState)
    graph.add_node("model", document_qa_model)
    graph.add_node("tools", document_qa_tools)
    graph.add_edge(START, "model")
    graph.add_conditional_edges("model", should_continue)
    graph.add_edge("tools", "model")
    return graph.compile()


DOCUMENT_QA_TOOLS = [search_web]
DOCUMENT_QA_TOOLS_BY_NAME = {
    tool.name: tool for tool in DOCUMENT_QA_TOOLS
}


@lru_cache
def get_document_qa_subgraph():
    document_qa_llm = get_llm().bind_tools(DOCUMENT_QA_TOOLS)
    return build_document_qa_subgraph(
        document_qa_llm,
        DOCUMENT_QA_TOOLS_BY_NAME,
    )


def _format_retrieval_matches(matches: list[dict], *, empty_message: str) -> str:
    if not matches:
        return empty_message

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


def _build_retrieval_query(user_query: str, task_desc: str | None) -> str:
    normalized_task = (task_desc or "").strip()
    lowered_task = normalized_task.lower()

    if not normalized_task:
        return user_query
    if normalized_task == user_query:
        return user_query
    if any(lowered_task.startswith(prefix) for prefix in GENERIC_TASK_PREFIXES):
        return user_query

    return f"{user_query}\nFocus: {normalized_task}"


def _document_qa_agent_node(
    state: WorkerInput,
) -> Command[Literal["synthesizer", "final_output_guardrail_node"]]:
    user_query = state.get("sanitized_query") or state["user_query"]
    task_desc = state.get("task_description", user_query)
    retrieval_context = list(state.get("retrieval_context", []))
    context = build_context(state.get("messages", []))
    logger.info("Document QA Agent task=%r", task_desc)

    engine = get_query_engine()
    retrieval_query = _build_retrieval_query(user_query, task_desc)
    local_matches = engine.retrieve_local_documents(
        household_id=state["household_id"],
        query=retrieval_query,
        session_id=state["session_id"],
        entry_id=state.get("entry_id"),
        doc_type=None,
        n_results=3,
    )
    combined_evidence = _format_retrieval_matches(
        local_matches,
        empty_message="No relevant saved-document passages were found.",
    )
    retrieval_context.append(combined_evidence)

    result = get_document_qa_subgraph().invoke(
        {
            "messages": [
                SystemMessage(content=DOCUMENT_QA_PROMPT),
                HumanMessage(
                    content=(
                        f"{context}Task: {task_desc}\n"
                        f"Customer query: {user_query}\n"
                        f"Retrieval Context: {combined_evidence}"
                    )
                ),
            ]
        }
    )
    answer = result["messages"][-1].content

    update = {
        "document_response": [
            {
                "agent": "document_qa_agent",
                "response": answer,
                "retrieval_context": retrieval_context,
            }
        ],
        "retrieval_context": retrieval_context,
    }
    return finalize_or_synthesize(state=state, answer=answer, update=update)


document_qa_agent = _document_qa_agent_node
