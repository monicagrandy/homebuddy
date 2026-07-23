from __future__ import annotations

from functools import lru_cache
import json
from typing import Literal

from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command
from pydantic import BaseModel

from backend.agents.prompts import OPERATIONS_PROMPT
from backend.agents.state import AgentState, build_context
from backend.config import get_logger
from backend.runtime import get_llm
from backend.schemas import CaseDraft, ContractorSuggestion, TaskDraft
from backend.tools.operations_tools import (
    get_contractor_suggestions,
    draft_case,
    draft_task,
)
from backend.workflow.state import WorkerInput

logger = get_logger(__name__)

def parse_tool_message(out):
    if isinstance(out, BaseModel):
        return out.model_dump()
    elif isinstance(out, list):
        if not out:
            return []
        if isinstance(out[0], BaseModel):
            return [item.model_dump() for item in out]
        return out
    return out

def build_operations_summary(
    *,
    contractor_suggestions: list[ContractorSuggestion],
    case_draft: CaseDraft | None,
    task_draft: TaskDraft | None,
) -> str:
    lines = ["Home operations summary:"]

    if contractor_suggestions:
        trade = contractor_suggestions[0].trade
        lines.append(
            f"- Found {len(contractor_suggestions)} contractor suggestion(s) for {trade}."
        )

    if case_draft:
        lines.append(
            f"- Case draft ready: {case_draft.title} (severity: {case_draft.severity})."
        )

    if task_draft:
        schedule_hint = task_draft.schedule_hint or "no schedule specified"
        lines.append(
            f"- Task draft ready: {task_draft.title} (schedule: {schedule_hint})."
        )

    if len(lines) == 1:
        lines.append("- No workflow outputs were generated.")

    return "\n".join(lines)

def build_home_operations_subgraph(agent_llm, tools_by_name):
    def home_operations_model(state: AgentState) -> dict:
        response = agent_llm.invoke(state["messages"])
        return {"messages": [response]}

    def home_operations_tools(state: AgentState) -> dict:
        last = state["messages"][-1]
        results = []
        for tc in last.tool_calls:
            name, args = tc["name"], tc["args"]
            out = tools_by_name[name].invoke(args) if name in tools_by_name else f"Unknown tool: {name}"
            parsed_out = parse_tool_message(out)
            payload = json.dumps({"name": name, "result": parsed_out})
            results.append(
                ToolMessage(
                    content=payload,
                    tool_call_id=tc["id"]
                    )
                )
        return {"messages": results}

    def should_continue(state: AgentState) -> str:
        last = state["messages"][-1]
        if hasattr(last, "tool_calls") and last.tool_calls:
            return "tools"
        return END

    graph = StateGraph(AgentState)
    graph.add_node("model", home_operations_model)
    graph.add_node("tools", home_operations_tools)
    graph.add_edge(START, "model")
    graph.add_conditional_edges("model", should_continue)
    graph.add_edge("tools", END)
    return graph.compile()


OPERATIONS_TOOLS = [get_contractor_suggestions, draft_task, draft_case]
OPERATIONS_TOOLS_BY_NAME = {t.name: t for t in OPERATIONS_TOOLS}


@lru_cache
def get_operations_subgraph():
    operations_llm = get_llm().bind_tools(OPERATIONS_TOOLS)
    return build_home_operations_subgraph(operations_llm, OPERATIONS_TOOLS_BY_NAME)

# Agent Node: Handles queries using the subgraph
def _home_operations_agent_node(state: WorkerInput) -> Command[Literal["synthesizer"]]:
    """Run the home operations agent via its model <> tools subgraph."""
    # Extract the user's query and task description from the input state
    user_query = state.get("sanitized_query") or state["user_query"]
    task_desc = state.get("task_description", user_query)
    context = build_context(state.get("messages", []))
    # Log this agent's execution
    logger.info("Home Operations Agent task=%r", task_desc)
    
    known_zip = state.get("household_zip_code")
    location_context = f"Known household zip code: {known_zip}\n" if known_zip else ""

    # Run the subgraph with the system prompt and user task
    result = get_operations_subgraph().invoke({"messages": [
        # Set the agent's system role and behavior
        SystemMessage(content=OPERATIONS_PROMPT),
        # Provide the task context and customer query
        HumanMessage(content=f"{context}{location_context}Task: {task_desc}\nCustomer query: {user_query}"),
    ]})

    # Extract the task draft, case draft and contractor suggestions from the subgraph execution
    messages = result["messages"]
    task_draft = None
    case_draft = None
    contractor_suggestions = []
    tool_messages_seen = 0
    for m in messages:
        if not isinstance(m, ToolMessage):
            continue
        tool_messages_seen += 1

        payload = json.loads(m.content)
        tool_name = payload["name"]
        data = payload["result"]
        if tool_name == "draft_task":
            task_draft = TaskDraft.model_validate(data)
        elif tool_name == "draft_case":
            case_draft = CaseDraft.model_validate(data)
        elif tool_name == "get_contractor_suggestions":
            contractor_suggestions = [
            ContractorSuggestion.model_validate(item)
            for item in data
        ]

    if tool_messages_seen == 0:
        logger.warning("Operations agent returned no tool calls for task=%r", task_desc)
        last_message = messages[-1] if messages else None
        answer = (
            getattr(last_message, "content", None)
            or "I couldn't generate workflow outputs for that request."
        )
    else:
        answer = build_operations_summary(
            contractor_suggestions=contractor_suggestions,
            case_draft=case_draft,
            task_draft=task_draft,
        )

    # Return the result and route to synthesizer
    return Command(
        # Store the agent's response for synthesis
        update={
            "operations_response": [{"agent": "home_operations_agent", "response": answer}],
            "task_draft": task_draft,
            "case_draft": case_draft,
            "contractor_suggestions": contractor_suggestions,
        },
        # Always route to synthesizer next
        goto="synthesizer"
    )
    
home_operations_agent = _home_operations_agent_node
