from backend.services.hazard_assessment_service import SafetyAssessment
from backend.services.routing_service import RoutingService
from backend.config import settings


def _non_hazard_assessment() -> SafetyAssessment:
    return SafetyAssessment(
        matched=False,
        urgency_level="low",
        should_escalate=False,
        stop_using=False,
        immediate_actions=[],
        contractor=[],
        rationale="No hazard language detected.",
    )


def test_routes_safety_language_first():
    assessment = SafetyAssessment(
        matched=True,
        urgency_level="critical",
        should_escalate=True,
        stop_using=True,
        immediate_actions=["turn off appliance if safe"],
        contractor=["appliance technician"],
        rationale="Detected fire/heat hazard language.",
    )

    decision = RoutingService().route("There is smoke coming from the dishwasher", assessment)

    assert decision is not None
    assert len(decision.route) == 1
    assert decision.route[0].agent == "safety_risk_agent"
    assert decision.route[0].task_description == "Provide immediate safety guidance based on the hazard assessment."
    assert decision.route_confidence == 1.0
    assert decision.route_explanation == "Detected hazard language requiring safety-first handling."
    assert decision.urgency_level == "critical"
    assert decision.should_parallelize is False


def test_routes_troubleshooting_queries_without_llm_classification():
    decision = RoutingService().route(
        "Why is my dishwasher not working?",
        _non_hazard_assessment(),
    )

    assert decision is not None
    assert [task.agent for task in decision.route] == ["document_qa_agent"]
    assert decision.route_confidence == 0.9
    assert decision.route_explanation == "Matched deterministic routing heuristics before LLM classification."
    assert decision.should_parallelize is False


def test_routes_coverage_queries_without_llm_classification():
    decision = RoutingService().route(
        "Is this repair covered by my warranty?",
        _non_hazard_assessment(),
    )

    assert decision is not None
    assert [task.agent for task in decision.route] == ["document_qa_agent"]


def test_routes_general_capability_questions_to_home_operations():
    decision = RoutingService().route(
        "What can you help me with as a homeowner?",
        _non_hazard_assessment(),
    )

    assert decision is not None
    assert [task.agent for task in decision.route] == ["home_operations_agent"]


def test_does_not_emit_home_operations_route_when_feature_flag_disabled(monkeypatch):
    monkeypatch.setattr(settings, "home_operations_enabled", False)

    decision = RoutingService().route(
        "Remind me tomorrow to follow up on my AC repair.",
        _non_hazard_assessment(),
    )

    assert decision is None


def test_routes_multi_domain_request_to_multiple_agents():
    decision = RoutingService().route(
        "My AC is not working and I need to find an HVAC contractor.",
        _non_hazard_assessment(),
    )

    assert decision is not None
    assert [task.agent for task in decision.route] == [
        "document_qa_agent",
        "home_operations_agent",
    ]
    assert decision.should_parallelize is True
