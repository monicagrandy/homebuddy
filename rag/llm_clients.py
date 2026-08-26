"""LLM client backends behind a single chat() interface.

AgenticQueryEngine talks to whichever client it's given without knowing whether
that means a local Ollama process (mobile/edge tier) or a cloud API (full/Alexa
tier). Both clients normalize their provider's tool-call format into a common
shape: {"raw_message": ..., "content": str | None, "tool_calls": [{"id", "name",
"arguments"}, ...]}. "raw_message" is kept in the provider's own format so it can
be appended straight back into that provider's conversation history.
"""
import json
import logging
import os
import openai

logger = logging.getLogger(__name__)

class OpenAIClient:
    """Cloud LLM client backed by the OpenAI API, for the full/Alexa-skill tier."""

    def __init__(self, model_name: str = "gpt-4o-mini", api_key: str | None = None):
        self.model_name = model_name
        self.client = openai.OpenAI(api_key=api_key or os.environ.get("OPENAI_API_KEY"))

    def chat(self, messages: list[dict], tools: list[dict] | None = None) -> dict:
        kwargs = {"model": self.model_name, "messages": messages}
        if tools:
            kwargs["tools"] = tools

        response = self.client.chat.completions.create(**kwargs)
        msg = response.choices[0].message

        tool_calls = [
            {"id": tc.id, "name": tc.function.name, "arguments": json.loads(tc.function.arguments)}
            for tc in (msg.tool_calls or [])
        ]
        return {"raw_message": msg.model_dump(), "content": msg.content, "tool_calls": tool_calls}
