from types import SimpleNamespace
from unittest.mock import patch

from backend.agents.safety import _safety_node
from backend.services.hazard_assessment_service import SafetyAssessment

def test_safety_node_happy_path():
    state = {
        "user_query": "Smoke is coming out of my oven",
        "task_description": "Provide immediate safety guidance.",
        "messages": [],
        "safety_assessment": SafetyAssessment(
            matched=True,
            urgency_level="critical",
            should_escalate=True,
            stop_using=True,
            immediate_actions=["turn off appliance if safe", "call 911 if active fire"],
            contractor=["appliance technician"],
            rationale="Detected fire/heat hazard language.",
        ),
    }
    fake_result = {
        "messages": [
            SimpleNamespace(
                content="Turn off the oven if it is safe to do so and call 911 if there is active fire."
            )
        ]
    }
    with patch("backend.agents.safety.safety_subgraph.invoke", return_value = fake_result):
        command = _safety_node(state)
    response = command.update["safety_response"][0]["response"]
    assert response is not None
    assert command.goto == "synthesizer"
    response = command.update["safety_response"][0]["response"]
    assert response == fake_result["messages"][-1].content

def test_safety_node_fallback():
    state = {
        "user_query": "Smoke is coming out of my oven",
        "task_description": "Provide immediate safety guidance.",
        "messages": [],
        "safety_assessment": SafetyAssessment(
            matched=True,
            urgency_level="critical",
            should_escalate=True,
            stop_using=True,
            immediate_actions=["turn off appliance if safe", "call 911 if active fire"],
            contractor=["appliance technician"],
            rationale="Detected fire/heat hazard language.",
        ),
    }

    with patch("backend.agents.safety.safety_subgraph.invoke", side_effect=Exception("boom")):
        command = _safety_node(state)

    response = command.update["safety_response"][0]["response"]
    assert response is not None
    assert command.goto == "synthesizer"
    assert "critical safety issue" in response.lower()
    assert "stop using" in response.lower()
    assert "call 911" in response.lower()
    assert "turn off" in response.lower()
    assert "technician" in response.lower()
