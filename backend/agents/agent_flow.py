from langgraph.types import Command


def finalize_or_synthesize(
    *,
    state: dict,
    answer: str,
    update: dict,
) -> Command:
    requires_synthesis = state.get("requires_synthesis", False)
    if requires_synthesis:
        return Command(update=update, goto="synthesizer")

    return Command(
        update={
            **update,
            "final_answer": answer,
        },
        goto="final_output_guardrail_node",
    )
