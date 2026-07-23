import json
from types import SimpleNamespace
from unittest.mock import patch

from langchain_core.messages import ToolMessage

from backend.agents.operations import _home_operations_agent_node


def test_operations_node_extracts_structured_outputs():
    state = {
        "user_query": "My AC isn't working. Find an HVAC technician near 90032 and remind me tomorrow to follow up.",
        "task_description": "Find an HVAC technician near 90032 and draft a follow-up reminder.",
        "messages": [],
        "household_id": 4,
        "session_id": "demo-session",
        "entry_id": None,
        "asset_id": None,
        "household_zip_code": "90032",
    }
    fake_result = {
        "messages": [
            ToolMessage(
                content=json.dumps(
                    {
                        "name": "get_contractor_suggestions",
                        "result": [
                            {
                                "business_name": "Pioneers Heating and Air",
                                "trade": "hvac",
                                "rating": 4.9,
                                "review_count": 416,
                                "phone": "+16262170559",
                                "url": "https://example.com/pioneers",
                                "reason_suggested": "HVAC specialists in the area",
                                "provider": "yelp_ai",
                                "online_submission": False,
                            }
                        ],
                    }
                ),
                tool_call_id="tool-1",
            ),
            ToolMessage(
                content=json.dumps(
                    {
                        "name": "draft_case",
                        "result": {
                            "title": "AC Not Working",
                            "summary": "The AC unit is not functioning properly and needs repair.",
                            "severity": "high",
                            "contractor_trade": "hvac",
                        },
                    }
                ),
                tool_call_id="tool-2",
            ),
            ToolMessage(
                content=json.dumps(
                    {
                        "name": "draft_task",
                        "result": {
                            "title": "Follow up on HVAC technician",
                            "notes": "Check on the status of the technician visit.",
                            "schedule_hint": "tomorrow",
                        },
                    }
                ),
                tool_call_id="tool-3",
            ),
            SimpleNamespace(content="I have contractor suggestions and drafts ready for your review."),
        ]
    }

    with patch("backend.agents.operations.get_operations_subgraph") as mock_graph:
        mock_graph.return_value.invoke.return_value = fake_result
        command = _home_operations_agent_node(state)

    assert command.goto == "synthesizer"
    answer = command.update["operations_response"][0]["response"]
    assert "Found 1 contractor suggestion(s) for hvac." in answer
    assert "Case draft ready: AC Not Working (severity: high)." in answer
    assert "Task draft ready: Follow up on HVAC technician (schedule: tomorrow)." in answer
    assert command.update["case_draft"].title == "AC Not Working"
    assert command.update["task_draft"].schedule_hint == "tomorrow"
    assert command.update["contractor_suggestions"][0].business_name == "Pioneers Heating and Air"


def test_operations_node_handles_no_tool_outputs():
    state = {
        "user_query": "What reminders do I have?",
        "task_description": "Answer the reminder status question.",
        "messages": [],
        "household_id": 4,
        "session_id": "demo-session",
        "entry_id": None,
        "asset_id": None,
        "household_zip_code": "90032",
    }
    fake_result = {
        "messages": [
            SimpleNamespace(content="You do not have any saved reminders yet. I can draft one if you'd like.")
        ]
    }

    with patch("backend.agents.operations.get_operations_subgraph") as mock_graph:
        mock_graph.return_value.invoke.return_value = fake_result
        command = _home_operations_agent_node(state)

    assert command.goto == "synthesizer"
    assert command.update["operations_response"][0]["response"] == "You do not have any saved reminders yet. I can draft one if you'd like."
    assert command.update["case_draft"] is None
    assert command.update["task_draft"] is None
    assert command.update["contractor_suggestions"] == []
