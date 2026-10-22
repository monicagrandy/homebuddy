"""Structured sources must survive the real LangGraph graph, which silently drops undeclared state keys."""

from types import SimpleNamespace
from unittest.mock import patch

from backend.services.query_service import QueryService
from backend.workflow.graph import build_graph
from backend.workflow.state import AgentTask, ClassificationResult


class PassThroughGuardrail:
    def anonymize_input(self, text: str) -> dict:
        return {"text": text}

    def check_toxicity(self, _text: str, fail_open_on_error: bool = False) -> bool:
        return False


MATCHES = [
    {
        "text": "Twist the filter counterclockwise and rinse it.",
        "metadata": {"entry_id": "bosch-manual", "household_id": 7, "source": "bosch-manual", "page": 12, "doc_type": "manual"},
    },
    {
        "text": "Reinsert the filter until it clicks.",
        "metadata": {"entry_id": "bosch-manual", "household_id": 7, "source": "bosch-manual", "page": 13, "doc_type": "manual"},
    },
]


def _fake_classifier_llm():
    classification = ClassificationResult(
        tasks=[AgentTask(agent="document_qa_agent", task_description="Explain how to clean the filter.")],
        requires_synthesis=False,
        reasoning="test",
        should_escalate=False,
    )
    classifier = SimpleNamespace(invoke=lambda *_args, **_kwargs: classification)
    return SimpleNamespace(with_structured_output=lambda *_args, **_kwargs: classifier)


def test_document_sources_reach_the_query_result_through_the_graph():
    guardrail = PassThroughGuardrail()
    engine = SimpleNamespace(retrieve_local_documents=lambda **_kwargs: MATCHES)
    subgraph = SimpleNamespace(
        invoke=lambda _state: {"messages": [SimpleNamespace(content="Twist the filter out and rinse it.")]}
    )

    with patch("backend.guardrails.nodes.input_node.get_safety_guardrail", return_value=guardrail), \
         patch("backend.guardrails.nodes.output_node.get_safety_guardrail", return_value=guardrail), \
         patch("backend.agents.orchestrator.get_workflow_llm", return_value=_fake_classifier_llm()), \
         patch("backend.agents.document_qa.get_query_engine", return_value=engine), \
         patch("backend.agents.document_qa.get_document_qa_subgraph", return_value=subgraph):
        result = QueryService(build_graph()).run_query(
            user_query="How do I clean the dishwasher filter?",
            user_id=1,
            household_id=7,
            session_id="s-1",
            household_zip_code=None,
        )

    assert result["answer"] == "Twist the filter out and rinse it."
    assert result["route"] == ["document_qa_agent"]
    assert result["sources"] == [
        {"entry_id": "bosch-manual", "source": "bosch-manual", "page": 12, "doc_type": "manual"},
        {"entry_id": "bosch-manual", "source": "bosch-manual", "page": 13, "doc_type": "manual"},
    ]
