from types import SimpleNamespace
from unittest.mock import patch

from backend.agents.document_qa import _document_qa_agent_node


def test_document_qa_agent_returns_direct_final_for_coverage_question():
    state = {
        "user_query": "Is my water heater covered under warranty?",
        "task_description": "Check if the water heater is covered under warranty and explain the coverage details.",
        "messages": [],
        "household_id": 4,
        "session_id": "demo-session",
        "entry_id": None,
        "asset_id": None,
        "household_zip_code": "90032",
    }
    fake_result = {
        "messages": [
            SimpleNamespace(
                content="Your water heater is covered for diagnosis, repair, or replacement up to the contract limit."
            )
        ]
    }

    fake_engine = SimpleNamespace(retrieve_local_documents=lambda **_kwargs: [])

    with patch("backend.agents.document_qa.get_query_engine", return_value=fake_engine), \
         patch("backend.agents.document_qa.get_document_qa_subgraph") as mock_graph:
        mock_graph.return_value.invoke.return_value = fake_result
        command = _document_qa_agent_node(state)

    assert command.goto == "final_output_guardrail_node"
    response = command.update["document_response"][0]
    assert response["agent"] == "document_qa_agent"
    assert response["response"] == "Your water heater is covered for diagnosis, repair, or replacement up to the contract limit."
    assert command.update["final_answer"] == response["response"]
    assert response["retrieval_context"] == ["No relevant saved-document passages were found."]
    assert command.update["retrieval_context"] == ["No relevant saved-document passages were found."]


def test_document_qa_agent_handles_missing_evidence_response():
    state = {
        "user_query": "Do I have proof of purchase for this dishwasher?",
        "task_description": "Check the saved documents for proof of purchase.",
        "messages": [],
        "household_id": 4,
        "session_id": "demo-session",
        "entry_id": None,
        "asset_id": None,
        "household_zip_code": "90032",
    }
    fake_result = {
        "messages": [
            SimpleNamespace(
                content="I cannot find any coverage, warranty or receipts associated with this product. Please contact the manufacturer for further assistance"
            )
        ]
    }

    fake_engine = SimpleNamespace(retrieve_local_documents=lambda **_kwargs: [])

    with patch("backend.agents.document_qa.get_query_engine", return_value=fake_engine), \
         patch("backend.agents.document_qa.get_document_qa_subgraph") as mock_graph:
        mock_graph.return_value.invoke.return_value = fake_result
        command = _document_qa_agent_node(state)

    assert command.goto == "final_output_guardrail_node"
    assert "I cannot find any coverage, warranty or receipts associated with this product" in command.update["document_response"][0]["response"]
