import pytest

from backend.services.hazard_assessment_service import HazardAssessmentService

@pytest.mark.parametrize(
    ("query", "expected_urgency", "expected_contractor", "expected_action_snippet"),
    [
        (
            "I smell gas near the water heater",
            "critical",
            "electrician",
            "leave area",
        ),
        (
            "My outlet is sparking",
            "critical",
            "electrician",
            "shut off power if safe",
        ),
        (
            "Smoke is coming out of my oven",
            "critical",
            "appliance technician",
            "turn off appliance if safe",
        ),
        (
            "There is sewage backing up in the basement drain",
            "high",
            "plumber",
            "shut off water if possible",
        ),
    ],
)
def test_assess_matches_hazard_categories(
    query: str,
    expected_urgency: str,
    expected_contractor: str,
    expected_action_snippet: str,
):
    assessment = HazardAssessmentService().assess(query)

    assert assessment.matched is True
    assert assessment.urgency_level == expected_urgency
    assert assessment.should_escalate is True
    assert assessment.stop_using is True
    assert isinstance(assessment.immediate_actions, list)
    assert assessment.immediate_actions
    assert isinstance(assessment.contractor, list)
    assert expected_contractor in assessment.contractor
    assert any(expected_action_snippet in action for action in assessment.immediate_actions)
    assert assessment.rationale is not None

def test_assess_returns_unmatched_for_non_hazard_query():
    assessment = HazardAssessmentService().assess(
        "How do I descale my dishwasher?"
    )

    assert assessment.matched is False
    assert assessment.urgency_level is None
    assert assessment.should_escalate is False
    assert assessment.stop_using is False
    assert assessment.immediate_actions == []
    assert assessment.contractor == []
    assert assessment.rationale is None

