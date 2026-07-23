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


def test_routes_contract_service_fee_to_coverage_deterministically():
    assessment = SafetyAssessment(matched=False)

    decision = RoutingService().route("What is the service call fee on my contract?", assessment)

    assert decision is not None
    assert len(decision.route) == 1
    assert decision.route[0].agent == "coverage_and_warranty_agent"
    assert decision.route_confidence == 0.95
    assert decision.should_parallelize is False


def test_does_not_route_informational_hvac_manual_question_to_home_ops():
    assessment = SafetyAssessment(matched=False)

    decision = RoutingService().route(
        "What does HVAC System Health Monitor do on the Nest thermostat?",
        assessment,
    )

    assert decision is not None
    assert len(decision.route) == 1
    assert decision.route[0].agent == "troubleshooting_agent"
