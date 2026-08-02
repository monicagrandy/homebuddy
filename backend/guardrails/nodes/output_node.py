from langgraph.graph import END
from langgraph.types import Command

from backend.runtime import get_safety_guardrail
from backend.workflow.state import HomeBuddyState

def final_output_guardrail_node(state: HomeBuddyState):
    # Cached singleton: constructing SafetyGuardrail loads Presidio/spaCy models,
    # which is far too expensive to do per request.
    output_guardrail = get_safety_guardrail()
    
    final_answer = state["final_answer"]

    sanitized = output_guardrail.anonymize_input(final_answer).get("text")

    if output_guardrail.check_toxicity(sanitized, fail_open_on_error=False):
        return Command(
            update={
                "output_blocked": True,
                "output_block_reason": "Blocked by output safety checks.",
                "final_answer": "HomeBuddy is not able to answer at this time. Please try again later.",
            },
            goto=END,
        )

    return Command(
        update={
            "final_answer": sanitized,
            "output_blocked": False,
            "output_block_reason": None,
        },
        goto=END,
    )
