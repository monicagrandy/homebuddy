from typing import Literal

from langchain_core.messages import HumanMessage, SystemMessage, ToolMessage
from langgraph.graph import START, StateGraph
from langgraph.types import Command

from backend.agents.prompts import SAFETY_PROMPT
from backend.agents.state import AgentState, build_context
from backend.config import get_logger
from backend.runtime import get_llm
from backend.services.hazard_assessment_service import SafetyAssessment
from backend.workflow.state import WorkerInput

logger = get_logger(__name__)

def build_safety_fallback_response(assessment: SafetyAssessment) -> str:
    if not assessment.matched:
        logger.error("Unmatched SafetyAssessment was routed to Safety Agent")
        return (
            "I could not confirm a specific safety hazard from the current assessment. "
        )

    lines = []

    if assessment.urgency_level == "critical":
        lines.append("This appears to be a critical safety issue.")
    elif assessment.urgency_level == "high":
        lines.append("This appears to be a serious safety issue.")
    else:
        lines.append("This may involve a safety concern.")

    if assessment.stop_using:
        lines.append("Stop using the affected appliance or system immediately.")

    if assessment.immediate_actions:
        lines.append("Take these steps now:")
        for action in assessment.immediate_actions:
            lines.append(f"- {action}")

    if assessment.should_escalate:
        lines.append("Do not rely on normal troubleshooting alone for this situation.")

    if assessment.contractor:
        lines.append(f"Recommended professional follow-up: {assessment.contractor}.")

    return "\n".join(lines)

# Safty subgraph: defines the menu agent's model-tools loop
def _safety_model(state: AgentState) -> dict:
    # Invoke the LLM with the current message history
    response = get_llm().invoke(state["messages"])
    # Return the LLM's response to be added to message history
    return {"messages": [response]}

# Build the agent's state graph
sg = StateGraph(AgentState)
# Add the model node that calls the LLM
sg.add_node("model", _safety_model)
# Start the graph by going to the model node
sg.add_edge(START, "model")
# Compile the graph into an executable form
safety_subgraph = sg.compile()

def _safety_node(state: WorkerInput) -> Command[Literal["synthesizer"]]:
    """Takes a deterministic SafetyAssesment and formats it for the user without overriding any of its data."""

    # Try to get the user query from state, or extract it from from the last message
    user_query = state.get("sanitized_query") or state["user_query"]
    if not user_query and state.get("messages"):
        # If no explicit query, use the last message as the query
        user_query = state["messages"][-1].content
    task_desc = state.get("task_description", user_query)
    assessment = state.get("safety_assessment")

    # Log the query being processed
    logger.info("Safety query=%r", user_query)

    # Format the conversation history for agent context
    context = build_context(state.get("messages", []))

    try:
        # Run the menu subgraph with the system prompt and user task
        result = safety_subgraph.invoke({"messages": [
            # Set the agent's system role and behavior
            SystemMessage(content=SAFETY_PROMPT),
            # Provide the task context and customer query
            HumanMessage(content=f"{context}Task: {task_desc}\nCustomer query: {user_query}\nSafety Assessment: {assessment}"),
        ]})
        # Extract the final answer from the subgraph execution
        answer = result["messages"][-1].content
    except Exception:
        answer = build_safety_fallback_response(assessment)

    # Return the result and route to synthesizer
    return Command(
        # Store the agent's response for synthesis
        update={"safety_response": [{"agent": "safety_agent", "response": answer}]},
        # Always route to synthesizer next
        goto="synthesizer"
    )
    
safety_risk_agent = _safety_node
