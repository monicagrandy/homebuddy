from backend.services.hazard_assessment_service import SafetyAssessment
from backend.services.routing_service import RoutingService


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


def test_returns_none_when_no_hazard_is_detected():
    assessment = SafetyAssessment(matched=False)

    decision = RoutingService().route("Why is my fridge making a loud clicking noise?", assessment)

    assert decision is not None
    assert len(decision.route) == 1
    assert decision.route[0].agent == "troubleshooting_agent"
    assert decision.route_confidence == 0.95
    assert decision.should_parallelize is False
