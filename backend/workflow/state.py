"""
LangGraph state definitions.

HomeBuddyState       – the main graph state shared by all nodes.
WorkerInput          – the payload sent to agent workers via Send().
AgentTask            – structured routing output from the orchestrator.
ClassificationResult – orchestrator's full decision.
"""

from __future__ import annotations
import operator
from typing import Annotated, List, Literal, TypedDict

from langchain_core.messages import AnyMessage
from pydantic import BaseModel, Field

from backend.config import get_logger
from backend.schemas import CaseDraft, ContractorSuggestion, TaskDraft
from backend.services.hazard_assessment_service import SafetyAssessment

logger = get_logger(__name__)

def agent_results_reducer(current: list[dict], update: list[dict]) -> list[dict]:
    """Custom reducer for agent_results that allows explicit resets.
    When update is empty, reset to []; otherwise append the update (like operator.add).
    This prevents stale results from prior turns from persisting."""
    # Empty update list signals a reset (clear all prior results)
    if not update:
        return []
    # Otherwise accumulate by concatenating current and update
    return current + update

class AgentTask(BaseModel):
    """A single task assigned to a specialist agent.
    
    Represents one piece of work in a potentially multi-agent request.
    For example, if a user asks 'Is my HVAC under warranty AND find me a technician' ,
    the orchestrator creates two AgentTask objects: one for coverage_and_warranty and one for home_operations.
    """

    # Name of the agent that will handle this task
    agent: Literal["troubleshooting_agent", "safety_risk_agent", "home_operations_agent", "coverage_and_warranty_agent"] = Field(
        description="Which agent handles this task"
    )
    # Description of the work the agent should perform
    task_description: str = Field(
        description="What the agent should do"
    )

class ClassificationResult(BaseModel):
    """Orchestrator's routing decision output.
    
    Contains the classification logic that determines which agents
    should handle the user's query and whether the results need to be merged.
    """
    # List of tasks to be dispatched to agents
    tasks: List[AgentTask] = Field(description="Tasks to dispatch")
    # Flag indicating if results from multiple agents need synthesis
    requires_synthesis: bool = Field(
        description="True when multiple agents must have their results merged."
    )
    # Explanation of the routing loging for debugging/logging
    reasoning: str = Field(description="Brief explanation of routing decision")
    urgency_hint: str | None = None
    should_escalate: bool

class HomeBuddyState(TypedDict):
    """
    Top-level state that flows through the entire main graph.

    This is the primary state object passed between all nodes in the main graph.
    It accumulates conversation history amd collects results from parallel agent execution.
    """

    # Conversation history
    # Uses operator.add to accumulate new messages to the list
    messages: Annotated[list[AnyMessage], operator.add]

    # The current raw user query 
    user_query: str

    # Routing and task management
    # List of tasks created by the orchestrator based on query classification
    tasks: list[AgentTask]

    sanitized_query: str

    input_blocked: bool

    input_block_reason: str | None

    output_blocked: bool
    
    output_block_reason: str | None

    safety_assessment: SafetyAssessment | None

    # Flag indicating if multiple agent responses need to be merged
    requires_synthesis: bool

    # Response from menu agent
    # Uses custom agent_results_reducer to allow resetting stale results
    troubleshooting_response: Annotated[list[dict], agent_results_reducer]

    # Response from order agent
    # Uses custom agent_results_reducer to allow resetting stale results
    coverage_response: Annotated[list[dict], agent_results_reducer]

    retrieval_context: Annotated[list[str], agent_results_reducer]

    # Response from order agent
    # Uses custom agent_results_reducer to allow resetting stale results
    safety_response: Annotated[list[dict], agent_results_reducer]

    # Response from order agent
    # Uses custom agent_results_reducer to allow resetting stale results
    operations_response: Annotated[list[dict], agent_results_reducer]


    session_id: str
    user_id: int
    household_id: int
    entry_id: str | None
    asset_id: int | None
    household_zip_code: str | None
    case_draft: CaseDraft | None
    task_draft: TaskDraft | None
    contractor_suggestions: list[ContractorSuggestion] | None
    route_confidence: float
    should_escalate: bool
    urgency_level: str | None

    # Final output
    # The synthesized response to be returned to the user
    final_answer: str

class WorkerInput(TypedDict):
    """Payload delivered to an agent worker node via Send().

    This represents the subset of HomeBuddyState that is passed to individual
    agent nodes when they are dispatched in parallel. Each agent receives
    its own copy of this input with the relevant conversation context and task.
    """

    # Conversation history up to this point (accumulates with operator.add)
    messages: Annotated[list[AnyMessage], operator.add]
    # The original user query that triggered this agent dispatch
    user_query: str
    # Specific task description for this agent to perform
    task_description: str
    safety_assessment: SafetyAssessment | None 
    user_id: int
    household_id: int
    session_id: str
    entry_id: str | None
    asset_id: int | None
    household_zip_code: str | None
    case_draft: CaseDraft | None
    task_draft: TaskDraft | None
    contractor_suggestions: list[ContractorSuggestion] | None
