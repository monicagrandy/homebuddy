from types import SimpleNamespace
from unittest.mock import patch

from backend.agents.troubleshooting import (
    _build_retrieval_query,
    _troubleshooting_agent_node,
)


def test_troubleshooting_agent_returns_response_to_synthesizer():
    state = {
        "user_query": "How do I turn on the AC with my thermostat?",
        "task_description": "Provide instructions on how to turn on the AC using the thermostat.",
        "messages": [],
        "household_id": 4,
        "session_id": "demo-session",
        "entry_id": None,
        "asset_id": None,
        "household_zip_code": "90032",
    }
    fake_result = {
        "messages": [
            SimpleNamespace(
                content="Set the thermostat to Cool mode and lower the set point below the current room temperature."
            )
        ]
    }

    fake_engine = SimpleNamespace(retrieve_local_documents=lambda **_kwargs: [])

    with patch("backend.agents.troubleshooting.get_query_engine", return_value=fake_engine), \
         patch("backend.agents.troubleshooting.get_troubleshooting_subgraph") as mock_graph:
        mock_graph.return_value.invoke.return_value = fake_result
        command = _troubleshooting_agent_node(state)

    assert command.goto == "synthesizer"
    response = command.update["troubleshooting_response"][0]
    assert response["agent"] == "troubleshooting_agent"
    assert response["response"] == "Set the thermostat to Cool mode and lower the set point below the current room temperature."
    assert response["retrieval_context"] == ["No relevant manual passages were found."]
    assert command.update["retrieval_context"] == ["No relevant manual passages were found."]


def test_troubleshooting_agent_preserves_contextual_answer():
    state = {
        "user_query": "My dishwasher is not draining. What should I check first?",
        "task_description": "Assist with troubleshooting the dishwasher draining issue.",
        "messages": [{"role": "user", "content": "The dishwasher was running last night."}],
        "household_id": 4,
        "session_id": "demo-session",
        "entry_id": "dishwasher-manual",
        "asset_id": None,
        "household_zip_code": "90032",
    }
    fake_result = {
        "messages": [
            SimpleNamespace(
                content="Check the drain filter and garbage disposal connection first."
            )
        ]
    }

    fake_engine = SimpleNamespace(retrieve_local_documents=lambda **_kwargs: [])

    with patch("backend.agents.troubleshooting.get_query_engine", return_value=fake_engine), \
         patch("backend.agents.troubleshooting.get_troubleshooting_subgraph") as mock_graph:
        mock_graph.return_value.invoke.return_value = fake_result
        command = _troubleshooting_agent_node(state)

    assert command.goto == "synthesizer"
    assert command.update["troubleshooting_response"][0]["response"] == "Check the drain filter and garbage disposal connection first."


def test_build_retrieval_query_prefers_user_query_for_generic_task_descriptions():
    user_query = "How do I set my thermostat to heat?"
    task_desc = "Help the user troubleshoot the reported issue."

    result = _build_retrieval_query(user_query, task_desc)

    assert result == user_query


def test_build_retrieval_query_appends_specific_focus_when_available():
    user_query = "Why is my dishwasher not draining?"
    task_desc = "Check likely causes related to the drain filter and garbage disposal connection."

    result = _build_retrieval_query(user_query, task_desc)

    assert result == (
        "Why is my dishwasher not draining?\n"
        "Focus: Check likely causes related to the drain filter and garbage disposal connection."
    )
