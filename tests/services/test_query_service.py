from langchain_core.messages import AIMessageChunk

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
            "tasks": [AgentTask(agent="document_qa_agent", task_description="Troubleshoot the dishwasher issue.")],
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
                    provider="serpapi_yelp",
                    source_attribution="Source: Yelp results via SerpApi.",
                    online_submission=True,
                )
            ],
        }

    def stream(self, _state: dict, stream_mode=None):
        raise AssertionError("stream() should not be used in this test")


class StreamingStubGraph:
    def __init__(self):
        self.calls = []

    def stream(self, state: dict, stream_mode=None):
        self.calls.append((state, stream_mode))
        yield ("values", {"sanitized_query": "How do I clean the dishwasher?", "input_blocked": False})
        yield (
            "values",
            {
                "tasks": [
                    AgentTask(
                        agent="document_qa_agent",
                        task_description="Troubleshoot the dishwasher issue.",
                    )
                ]
            },
        )
        yield ("updates", {"document_qa_agent": {"document_response": []}})
        yield (
            "messages",
            (
                AIMessageChunk(content="Clean "),
                {"langgraph_node": "synthesizer"},
            ),
        )
        yield (
            "messages",
            (
                AIMessageChunk(content="the filter."),
                {"langgraph_node": "synthesizer"},
            ),
        )
        yield (
            "values",
            {
                "final_answer": "Clean the filter.",
                "user_query": state["user_query"],
                "sanitized_query": "How do I clean the dishwasher?",
                "input_blocked": False,
                "tasks": [
                    AgentTask(
                        agent="document_qa_agent",
                        task_description="Troubleshoot the dishwasher issue.",
                    )
                ],
                "route_confidence": 0.91,
                "route_explanation": "Manual troubleshooting request",
                "retrieval_context": [],
                "should_escalate": False,
                "contractor_suggestions": [],
            },
        )


def test_run_query_returns_expected_backend_shape():
    graph = StubGraph()
    service = QueryService(graph)

    result = service.run_query(
        user_query="How do I descale the dishwasher?",
        user_id=17,
        session_id="session-1",
        entry_id="dishwasher-manual",
        household_id=4,
        asset_id=11,
        household_zip_code="98101",
        messages=[{"role": "user", "content": "Previous question"}],
    )

    assert result["answer"] == "Grounded answer"
    assert result["route"] == ["document_qa_agent"]
    assert result["route_confidence"] == 0.87
    assert result["route_explanation"] == "Classified as a troubleshooting/manual Q&A request."
    assert result["case_draft"].title == "Dishwasher issue"
    assert result["task_draft"].schedule_hint == "tomorrow"
    assert result["contractor_suggestions"][0].business_name == "Appliance Pros"

    assert graph.calls == [{
        "user_query": "How do I descale the dishwasher?",
        "user_id": 17,
        "household_id": 4,
        "session_id": "session-1",
        "entry_id": "dishwasher-manual",
        "asset_id": 11,
        "household_zip_code": "98101",
        "messages": [{"role": "user", "content": "Previous question"}],
        "stream_final_answer": False,
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


def test_stream_query_emits_statuses_tokens_and_final_payload():
    graph = StreamingStubGraph()
    service = QueryService(graph)

    events = list(
        service.stream_query(
            user_query="How do I clean the dishwasher filter?",
            user_id=17,
            session_id="session-1",
            entry_id=None,
            household_id=4,
            asset_id=None,
            household_zip_code="98101",
            messages=[],
        )
    )

    assert events == [
        {
            "type": "user_accepted",
            "sanitized_query": "How do I clean the dishwasher?",
            "input_blocked": False,
        },
        {
            "type": "status",
            "message": "🧭 Routing complete: document_qa_agent",
        },
        {
            "type": "status",
            "message": "📚 Document agent checked the saved docs and finished its pass.",
        },
        {
            "type": "status",
            "message": "✨ Writing the final response.",
        },
        {"type": "token", "text": "Clean "},
        {"type": "token", "text": "the filter."},
        {
            "type": "final",
            "result": {
                "answer": "Clean the filter.",
                "query": "How do I clean the dishwasher filter?",
                "sanitized_query": "How do I clean the dishwasher?",
                "input_blocked": False,
                "route": ["document_qa_agent"],
                "route_confidence": 0.91,
                "route_explanation": "Manual troubleshooting request",
                "urgency_level": None,
                "should_escalate": False,
                "case_draft": None,
                "task_draft": None,
                "contractor_suggestions": [],
                "retrieval_context": [],
            },
        },
    ]

    assert graph.calls == [
        (
            {
                "user_query": "How do I clean the dishwasher filter?",
                "user_id": 17,
                "household_id": 4,
                "session_id": "session-1",
                "entry_id": None,
                "asset_id": None,
                "household_zip_code": "98101",
                "messages": [],
                "stream_final_answer": True,
            },
            ["updates", "values", "messages"],
        )
    ]
