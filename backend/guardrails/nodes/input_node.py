from typing import Literal

from langgraph.graph import END
from langgraph.types import Command

from backend.runtime import get_safety_input_guardrail
from backend.workflow.state import HomeBuddyState

def input_guardrail_node(state: HomeBuddyState) -> Command[Literal["orchestrator"]]:
    input_guardrail = get_safety_input_guardrail()
    
    query = state["user_query"]

    sanitized = input_guardrail.anonymize_input(query).get("text")

    if input_guardrail.check_toxicity(sanitized, fail_open_on_error=True):
        return Command(
            update={
                "input_blocked": True,
                "input_block_reason": "Blocked by input safety checks.",
                "final_answer": "I can't help with that request as written.",
            },
            goto=END,
        )

    return Command(
        update={
            "sanitized_query": sanitized,
            "input_blocked": False,
            "input_block_reason": None,
        },
        goto="orchestrator",
    )
