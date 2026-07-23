# Agent Subgraphs (model ⇄ tools)
# These are compiled graphs that handle the model calling tools loop

import operator
from typing import Annotated, TypedDict

from langchain_core.messages import AIMessage, AnyMessage, HumanMessage
from langgraph.graph import END

class AgentState(TypedDict):
    """Minimal state for the model <> tools subgraph loop.
    Uses operator.add to append new messages to the list (accumulation)."""

    # List of messages representing the conversation history within the subgraph
    # operator.add means each node's returned messages are appended, not replaced
    messages: Annotated[list[AnyMessage], operator.add]

def build_context(messages: list[AnyMessage]) -> str:
    """Format prior conversation turns as readable text for agent context.
    This gives the subgraph agent awareness of the broader conversation history."""
    # If there are no messages, return empty context
    if not messages:
        return ""
    # Store formatted message lines
    parts = []
    # Process each message in the conversation history
    for m in messages:
        # Label human (customer) messages
        if isinstance(m, HumanMessage):
            parts.append(f"Customer: {m.content}")
        # Label AI (assistant) messages
        if isinstance(m, AIMessage):
            parts.append(f"Assistant: {m.content}")
    # If no human or AI messages were found (e.g. only system/tool messages), return empty
    if not parts:
        return ""
    # Prefix with a header so the agent knows this is prior context, not the current task
    return "CONVERSATION SO FAR:\n" + "\n".join(parts) + "\n\n"
