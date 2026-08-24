from types import SimpleNamespace
from unittest.mock import patch

from backend.agents.synthesizer import synthesizer_node


def test_synthesizer_returns_bounded_fallback_when_no_agents_respond():
    state = {
        "user_query": "What's the best movie this weekend?",
        "messages": [],
        "document_response": [],
        "safety_response": [],
        "operations_response": [],
        "case_draft": None,
        "task_draft": None,
        "contractor_suggestions": [],
    }

    command = synthesizer_node(state)

    assert command.goto == "final_output_guardrail_node"
    assert command.update["final_answer"] == "Sorry, I couldn't process that request. Please try again"


def test_synthesizer_passes_through_single_agent_response():
    state = {
        "user_query": "How do I reset my thermostat?",
        "messages": [],
        "document_response": [{"agent": "document_qa_agent", "response": "Use the reset option in settings."}],
        "safety_response": [],
        "operations_response": [],
        "case_draft": None,
        "task_draft": None,
        "contractor_suggestions": [],
    }

    command = synthesizer_node(state)

    assert command.goto == "final_output_guardrail_node"
    assert command.update["final_answer"] == "Use the reset option in settings."


def test_synthesizer_merges_multi_agent_outputs_with_llm():
    state = {
        "user_query": "My AC isn't working. Find an HVAC technician near 90032.",
        "messages": [],
        "document_response": [{"agent": "document_qa_agent", "response": "Check thermostat and breaker."}],
        "safety_response": [],
        "operations_response": [{"agent": "home_operations_agent", "response": "Two HVAC contractors are available."}],
        "case_draft": None,
        "task_draft": None,
        "contractor_suggestions": [],
    }

    fake_settings = SimpleNamespace(
        invoke=lambda *_args, **_kwargs: SimpleNamespace(
                content="Check the thermostat and breaker first. I also found two HVAC contractors near you."
            )
    )

    with patch("backend.agents.synthesizer.get_workflow_llm", return_value=fake_settings):
        command = synthesizer_node(state)

    assert command.goto == "final_output_guardrail_node"
    assert "two HVAC contractors" in command.update["final_answer"]
