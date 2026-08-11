# Synthesizer: Merges parallel responses from one or more agents into final answer

from typing import Literal

from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.types import Command

from backend.agents.prompts import SYNTHESIZER_PROMPT
from backend.config import get_logger
from backend.runtime import get_workflow_llm
from backend.workflow.state import HomeBuddyState

logger = get_logger(__name__)

def synthesizer_node(state: HomeBuddyState) -> Command[Literal["final_output_guardrail_node"]]:
    """Merge results from one or more agents into a single user-facing reply.
    Handles both single and multi-agent responses."""
    # Collect response lists from each agent (empty list if an agent didn't run)
    troubleshooting_response = state.get("troubleshooting_response", [])
    coverage_response = state.get("coverage_response", [])
    retrieval_context = state.get("retrieval_context")
    safety_response = state.get("safety_response", [])
    operations_response = state.get("operations_response", []) 
        
    # Combine into a single flat list to handle 0, 1, or 2 agents uniformly
    all_results = troubleshooting_response + coverage_response + safety_response + operations_response 
     # Get the original user query for context when calling the LLM
    user_query = state.get("sanitized_query") or state["user_query"]

    # If no agent produced results, return an error message
    if not all_results:
        logger.warning("Synthesizer received no agent results")
        return Command(
            update = {"final_answer": "Sorry, I couldn't process that request. Please try again"},
            goto = "final_output_guardrail_node"
        )
        

    # If only one agent responded, pass its answer through directly without calling the LLM
    if len(all_results) == 1:
        logger.info("Synthesizer single-agent pass-through")
        return Command(
            update = {"final_answer": all_results[0]["response"]},
            goto = "final_output_guardrail_node"
        )
         
    # Multiple agents responded — use the LLM to merge them coherently
    logger.info("Synthesizer  merging %d agent responses", len(all_results))

    # Format all agent responses with their agent labels so the LLM knows which came from where
    parts = "\n\n".join(
        f"[{r['agent'].upper()}]:\n{r['response']}" for r in all_results
    )

    # Call the LLM with the synthesizer prompt and the combined agent responses
    merged = get_workflow_llm().invoke([
        SystemMessage(content=SYNTHESIZER_PROMPT),
        HumanMessage(content=f"User query: {user_query}\n\n{parts}"),
    ])
    # Return the merged response as the final answer
    return Command(
        update = {"final_answer": merged.content},
        goto = "final_output_guardrail_node"
    ) 

synthesizer_agent = synthesizer_node
