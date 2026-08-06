# Orchestrator node: entry point for the main graph; decides when agents should handle the request

from collections import OrderedDict
from typing import Literal

from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.types import Command, Send

from backend.agents.prompts import ORCHESTRATOR_PROMPT
from backend.agents.state import build_context
from backend.config import get_logger
from backend.runtime import (
    get_hazard_assessment_service,
    get_routing_service,
    get_workflow_llm,
)
from backend.services.routing_service import RouteDecision
from backend.workflow.state import AgentTask, ClassificationResult, HomeBuddyState

logger = get_logger(__name__)

def _confidence_for_llm_result(classification: ClassificationResult) -> float:
    if len(classification.tasks) == 1:
        return 0.7
    return 0.6

def _map_classification_result_to_route_decision(classification: ClassificationResult):
    return RouteDecision(
        route = classification.tasks,
        route_explanation = classification.reasoning,
        should_parallelize = classification.requires_synthesis or len(classification.tasks) > 1,
        urgency_level = classification.urgency_hint or "medium",
        route_confidence = _confidence_for_llm_result(classification),
        should_escalate = classification.should_escalate
    )

def _merge_duplicate_agent_tasks(tasks: list[AgentTask]) -> list[AgentTask]:
    grouped: OrderedDict[str, list[str]] = OrderedDict()

    for task in tasks:
        grouped.setdefault(task.agent, [])
        grouped[task.agent].append(task.task_description.strip())

    merged: list[AgentTask] = []

    for agent, descriptions in grouped.items():
        unique_descriptions = []
        seen = set()

        for description in descriptions:
            normalized = " ".join(description.split()).lower()
            if normalized not in seen:
                seen.add(normalized)
                unique_descriptions.append(description)

        merged.append(
            AgentTask(
                agent=agent,
                task_description=" ".join(unique_descriptions),
            )
        )
    logger.info(f"merged agent tasks: {merged}")
    return merged

def _orchestrator_node(state: HomeBuddyState) -> Command[Literal["safety_risk_agent", "troubleshooting_agent", "coverage_and_warranty_agent", "home_operations_agent", "final_output_guardrail_node"]]:
    """Classify the user query and dispatch it to the appropriate agent(s).
    Can route to multiple if synthesis is needed."""

    safety_service = get_hazard_assessment_service()
    routing_service = get_routing_service()

    # Try to get the user query from state, or extract it from from the last message
    user_query = state.get("sanitized_query") or state["user_query"]
    if not user_query and state.get("messages"):
        # If no explicit query, use the last message as the query
        user_query = state["messages"][-1].content

    # Log the query being processed
    logger.info("Orchestrator query=%r", user_query)
    context = build_context(state.get("messages", []))

    assessment = safety_service.assess(user_query)
    decision = routing_service.route(user_query, assessment)

    if not decision:
        # Use LLM with structured output to get a ClassificationResult object
        classifier = get_workflow_llm().with_structured_output(ClassificationResult)
        try:
            # Invoke the classifier with the orchestrator prompt
            classification = classifier.invoke([
                SystemMessage(content=ORCHESTRATOR_PROMPT),
                HumanMessage(
                    content=(
                        f"{context}CURRENT USER MESSAGE:\n{user_query}\n\n"
                        "Use the prior conversation when the current message is a follow-up, short reply, "
                        "location answer, confirmation, or scheduling detail."
                    )
                )
            ])
            decision = _map_classification_result_to_route_decision(classification)
        except Exception:
            # If classification fails, log the error and provide a default fallback
            logger.exception("Classification failed")
            decision = RouteDecision(
                route = [],
                route_explanation = "Fallback: classification error",
                should_parallelize = False,
                urgency_level = "none",
                route_confidence = 0,
                should_escalate = False
            )
    
    # Log which agents will be used and whether synthesis is needed
    logger.info("  routing=%s  synthesis=%s",
                [t.agent for t in decision.route],
                decision.should_parallelize)
    
    merged_tasks = _merge_duplicate_agent_tasks(decision.route)
    requires_synthesis = len(merged_tasks) > 1

    # No agent to route to
    if len(merged_tasks) == 0:
        return Command(
            update = {"final_answer": "I'm not sure how to answer that. I can help with home maintenance, troubleshooting, coverage, and safety questions."},
            goto = "final_output_guardrail_node"
    ) 

    return Command(
        update={
            "safety_assessment": assessment,
            "tasks": merged_tasks,
            "requires_synthesis": requires_synthesis,
            "sanitized_query": user_query,
            "final_answer": "",
            "route_confidence": decision.route_confidence,
            "urgency_level": decision.urgency_level,
            "should_escalate": decision.should_escalate       
        },
        goto=[
            Send(
                task.agent,
                {
                    "messages": state.get("messages", []),
                    "user_query": user_query,
                    "task_description": task.task_description,
                    "safety_assessment": assessment,
                    "household_id": state.get("household_id"),
                    "session_id": state.get("session_id"),
                    "asset_id": state.get("asset_id"),
                    "entry_id": state.get("entry_id"),
                    "household_zip_code": state.get("household_zip_code"),
                },
            )
            for task in merged_tasks
        ],
)

orchestrator_agent = _orchestrator_node
