from types import SimpleNamespace
from unittest.mock import patch

from backend.agents.orchestrator import (
    _merge_duplicate_agent_tasks,
    HOME_OPERATIONS_CAPABILITIES_MESSAGE,
    HOME_OPERATIONS_DISABLED_MESSAGE,
)
from backend.config import settings
from backend.services.routing_service import RouteDecision
from backend.services.hazard_assessment_service import SafetyAssessment
from backend.workflow.state import AgentTask


def test_merge_duplicate_agent_tasks_combines_home_operations_requests():
    tasks = [
        AgentTask(agent="home_operations_agent", task_description="Find an HVAC technician near 90032."),
        AgentTask(agent="home_operations_agent", task_description="Remind the user to follow up tomorrow."),
        AgentTask(agent="troubleshooting_agent", task_description="Assist with the AC issue."),
    ]

    merged = _merge_duplicate_agent_tasks(tasks)

    assert len(merged) == 2
    assert merged[0].agent == "home_operations_agent"
    assert merged[0].task_description == "Find an HVAC technician near 90032. Remind the user to follow up tomorrow."
    assert merged[1].agent == "troubleshooting_agent"


def test_merge_duplicate_agent_tasks_deduplicates_identical_descriptions():
    tasks = [
        AgentTask(agent="home_operations_agent", task_description="Find an HVAC technician near 90032."),
        AgentTask(agent="home_operations_agent", task_description=" Find an HVAC technician near 90032. "),
    ]

    merged = _merge_duplicate_agent_tasks(tasks)

    assert len(merged) == 1
    assert merged[0].agent == "home_operations_agent"
    assert merged[0].task_description == "Find an HVAC technician near 90032."


def test_hazardous_query_suppresses_non_safety_routes():
    state = {
        "user_query": "My AC isn't working and I smell burning from the panel. Find me an HVAC technician near 90032.",
        "messages": [],
        "household_id": 4,
        "session_id": "demo-session",
        "asset_id": None,
        "entry_id": None,
        "household_zip_code": "90032",
    }
    assessment = SafetyAssessment(
        matched=True,
        urgency_level="critical",
        should_escalate=True,
        stop_using=True,
        immediate_actions=["stop using the system", "shut off power if safe"],
        contractor=["electrician"],
        rationale="Detected electrical hazard language.",
    )

    fake_safety_service = SimpleNamespace(assess=lambda _query: assessment)

    with patch("backend.agents.orchestrator.get_hazard_assessment_service", return_value=fake_safety_service):
        command = _merge_test_orchestrator_node(state)

    assert command.update["tasks"][0].agent == "safety_risk_agent"
    assert len(command.update["tasks"]) == 1
    assert command.update["requires_synthesis"] is False
    assert len(command.goto) == 1
    assert command.goto[0].node == "safety_risk_agent"


def test_classification_failure_returns_no_tasks_for_non_hazard_query():
    state = {
        "user_query": "What's the best movie this weekend?",
        "messages": [],
        "household_id": 4,
        "session_id": "demo-session",
        "asset_id": None,
        "entry_id": None,
        "household_zip_code": "90032",
    }
    assessment = SafetyAssessment(matched=False)

    fake_safety_service = SimpleNamespace(assess=lambda _query: assessment)
    fake_routing_service = SimpleNamespace(route=lambda *_args, **_kwargs: None)
    fake_classifier = SimpleNamespace(
        invoke=lambda *_args, **_kwargs: (_ for _ in ()).throw(Exception("classification failed"))
    )
    fake_llm = SimpleNamespace(with_structured_output=lambda *_args, **_kwargs: fake_classifier)

    with patch("backend.agents.orchestrator.get_hazard_assessment_service", return_value=fake_safety_service), \
         patch("backend.agents.orchestrator.get_routing_service", return_value=fake_routing_service), \
         patch("backend.agents.orchestrator.get_workflow_llm", return_value=fake_llm):
        command = _merge_test_orchestrator_node(state)

    assert command.goto == "final_output_guardrail_node"
    assert command.update["final_answer"] == "I'm not sure how to answer that. I can help with home maintenance, troubleshooting, coverage, and safety questions."


def test_home_operations_request_is_declined_when_feature_flag_disabled(monkeypatch):
    monkeypatch.setattr(settings, "home_operations_enabled", False)
    state = {
        "user_query": "Find me an HVAC technician near 90032.",
        "messages": [],
        "household_id": 4,
        "session_id": "demo-session",
        "asset_id": None,
        "entry_id": None,
        "household_zip_code": "90032",
    }
    assessment = SafetyAssessment(matched=False, urgency_level="low", should_escalate=False)
    fake_safety_service = SimpleNamespace(assess=lambda _query: assessment)

    with patch("backend.agents.orchestrator.get_hazard_assessment_service", return_value=fake_safety_service):
        command = _merge_test_orchestrator_node(state)

    assert command.goto == "final_output_guardrail_node"
    assert command.update["final_answer"] == HOME_OPERATIONS_DISABLED_MESSAGE
    assert command.update["tasks"] == []


def test_mixed_request_is_declined_when_feature_flag_disabled(monkeypatch):
    monkeypatch.setattr(settings, "home_operations_enabled", False)
    state = {
        "user_query": "My AC is not working. Find me an HVAC technician near 90032.",
        "messages": [],
        "household_id": 4,
        "session_id": "demo-session",
        "asset_id": None,
        "entry_id": None,
        "household_zip_code": "90032",
    }
    assessment = SafetyAssessment(matched=False, urgency_level="low", should_escalate=False)
    fake_safety_service = SimpleNamespace(assess=lambda _query: assessment)

    with patch("backend.agents.orchestrator.get_hazard_assessment_service", return_value=fake_safety_service):
        command = _merge_test_orchestrator_node(state)

    assert command.goto == "final_output_guardrail_node"
    assert command.update["final_answer"] == HOME_OPERATIONS_DISABLED_MESSAGE


def test_hazard_still_routes_to_safety_when_feature_flag_disabled(monkeypatch):
    monkeypatch.setattr(settings, "home_operations_enabled", False)
    state = {
        "user_query": "There is smoke coming from my oven. Find me a technician.",
        "messages": [],
        "household_id": 4,
        "session_id": "demo-session",
        "asset_id": None,
        "entry_id": None,
        "household_zip_code": "90032",
    }
    assessment = SafetyAssessment(
        matched=True,
        urgency_level="critical",
        should_escalate=True,
        stop_using=True,
        immediate_actions=["turn off power if safe"],
        contractor=["electrician"],
        rationale="Detected hazard.",
    )
    fake_safety_service = SimpleNamespace(assess=lambda _query: assessment)

    with patch("backend.agents.orchestrator.get_hazard_assessment_service", return_value=fake_safety_service):
        command = _merge_test_orchestrator_node(state)

    assert len(command.update["tasks"]) == 1
    assert command.update["tasks"][0].agent == "safety_risk_agent"


def test_general_capability_question_gets_beta_capabilities_message_when_feature_flag_disabled(monkeypatch):
    monkeypatch.setattr(settings, "home_operations_enabled", False)
    state = {
        "user_query": "What can you help me with as a homeowner?",
        "messages": [],
        "household_id": 4,
        "session_id": "demo-session",
        "asset_id": None,
        "entry_id": None,
        "household_zip_code": "90032",
    }
    assessment = SafetyAssessment(matched=False, urgency_level="low", should_escalate=False)
    fake_safety_service = SimpleNamespace(assess=lambda _query: assessment)

    with patch("backend.agents.orchestrator.get_hazard_assessment_service", return_value=fake_safety_service):
        command = _merge_test_orchestrator_node(state)

    assert command.goto == "final_output_guardrail_node"
    assert command.update["final_answer"] == HOME_OPERATIONS_CAPABILITIES_MESSAGE


def _merge_test_orchestrator_node(state: dict):
    from backend.agents.orchestrator import _orchestrator_node

    return _orchestrator_node(state)
