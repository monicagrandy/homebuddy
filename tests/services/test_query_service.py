from backend.schemas import CaseDraft, ContractorSuggestion, TaskDraft
from backend.services.query_service import QueryService
from backend.workflow.state import AgentTask


class StubGraph:
    def __init__(self):
        self.calls = []

    def invoke(self, state: dict):
        self.calls.append(state)
        return {
            "final_answer": "Grounded answer",
            "tasks": [AgentTask(agent="troubleshooting_agent", task_description="Troubleshoot the dishwasher issue.")],
            "route_confidence": 0.87,
            "route_explanation": "Classified as a troubleshooting/manual Q&A request.",
            "retrieval_context": [{"source": "manual.pdf", "page": 2, "doc_type": "manual", "url": None}],
            "urgency_level": None,
            "should_escalate": False,
            "case_draft": CaseDraft(
                title="Dishwasher issue",
                summary="Dishwasher is not draining properly.",
                severity="medium",
                contractor_trade="appliance repair",
            ),
            "task_draft": TaskDraft(
                title="Check drain filter",
                notes="Inspect and clean the dishwasher drain filter.",
                schedule_hint="tomorrow",
            ),
            "contractor_suggestions": [
                ContractorSuggestion(
                    business_name="Appliance Pros",
                    trade="appliance repair",
                    rating=4.8,
                    review_count=120,
                    phone="555-0100",
                    url="https://example.com/appliance-pros",
                    reason_suggested="Highly rated local appliance repair shop",
                    provider="yelp_ai",
                    online_submission=True,
                )
            ],
        }


def test_run_query_returns_expected_backend_shape():
    graph = StubGraph()
    service = QueryService(graph)

    result = service.run_query(
        user_query="How do I descale the dishwasher?",
        session_id="session-1",
        entry_id="dishwasher-manual",
        household_id=4,
        asset_id=11,
        household_zip_code="98101",
        messages=[{"role": "user", "content": "Previous question"}],
    )

    assert result["answer"] == "Grounded answer"
    assert result["route"] == ["troubleshooting_agent"]
    assert result["route_confidence"] == 0.87
    assert result["route_explanation"] == "Classified as a troubleshooting/manual Q&A request."
    assert result["case_draft"].title == "Dishwasher issue"
    assert result["task_draft"].schedule_hint == "tomorrow"
    assert result["contractor_suggestions"][0].business_name == "Appliance Pros"

    assert graph.calls == [{
        "user_query": "How do I descale the dishwasher?",
        "household_id": 4,
        "session_id": "session-1",
        "entry_id": "dishwasher-manual",
        "asset_id": 11,
        "household_zip_code": "98101",
        "messages": [{"role": "user", "content": "Previous question"}],
    }]


def test_to_query_response_uses_safe_defaults():
    service = QueryService(StubGraph())

    result = service._to_query_response({})

    assert result == {
        "answer": "I couldn't complete that request.",
        "query": None,
        "sanitized_query": None,
        "input_blocked": False,
        "route": [],
        "route_confidence": 0.0,
        "route_explanation": None,
        "urgency_level": None,
        "should_escalate": False,
        "case_draft": None,
        "task_draft": None,
        "contractor_suggestions": [],
        "retrieval_context": []
    }
