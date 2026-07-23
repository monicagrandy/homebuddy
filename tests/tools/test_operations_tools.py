import httpx

from unittest.mock import patch

from backend.schemas import ContractorSuggestion
from backend.tools.operations_tools import get_contractor_suggestions


def test_get_contractor_suggestions_returns_parsed_results():
    fake_payload = {"entities": [{"businesses": [{"name": "Pioneers Heating and Air"}]}]}
    fake_suggestions = [
        ContractorSuggestion(
            business_name="Pioneers Heating and Air",
            trade="hvac",
            rating=4.9,
            review_count=416,
            phone="+16262170559",
            url="https://example.com/pioneers",
            reason_suggested="HVAC specialists in the area",
            provider="yelp_ai",
            online_submission=False,
        )
    ]

    with patch("backend.tools.operations_tools.search_yelp_ai_cached", return_value=fake_payload), \
         patch("backend.tools.operations_tools.parse_yelp_ai_entities_to_contractor_suggestions", return_value=fake_suggestions):
        result = get_contractor_suggestions.invoke({"trade": "hvac", "zip_code": "90032"})

    assert result == fake_suggestions


def test_get_contractor_suggestions_returns_empty_list_when_api_key_missing():
    with patch("backend.tools.operations_tools.search_yelp_ai_cached", side_effect=ValueError("Missing Yelp API key.")):
        result = get_contractor_suggestions.invoke({"trade": "hvac", "zip_code": "90032"})

    assert result == []


def test_get_contractor_suggestions_returns_empty_list_on_http_error():
    with patch("backend.tools.operations_tools.search_yelp_ai_cached", side_effect=httpx.HTTPStatusError("boom", request=None, response=None)):
        result = get_contractor_suggestions.invoke({"trade": "hvac", "zip_code": "90032"})

    assert result == []
