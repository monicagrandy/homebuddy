import httpx
import pytest

from unittest.mock import patch

from backend.config import settings
from backend.schemas import ContractorSuggestion
from backend.tools.contractor_suggestions.contractor_search import ContractorSearchLimitExceeded
from backend.tools.operations_tools import get_contractor_suggestions


def test_get_contractor_suggestions_returns_parsed_results():
    fake_payload = {"organic_results": [{"title": "Pioneers Heating and Air"}]}
    fake_suggestions = [
        ContractorSuggestion(
            business_name="Pioneers Heating and Air",
            trade="hvac",
            rating=4.9,
            review_count=416,
            phone="+16262170559",
            url="https://example.com/pioneers",
            reason_suggested="HVAC specialists in the area",
            provider="serpapi_yelp",
            source_attribution="Source: Yelp results via SerpApi.",
            online_submission=False,
        )
    ]

    with patch("backend.tools.operations_tools.enforce_user_monthly_contractor_search_limit"), \
         patch("backend.tools.operations_tools.record_user_monthly_contractor_search"), \
         patch("backend.tools.operations_tools.search_contractor_directory_cached", return_value=fake_payload), \
         patch("backend.tools.operations_tools.parse_serpapi_yelp_results_to_contractor_suggestions", return_value=fake_suggestions):
        result = get_contractor_suggestions.invoke({"trade": "hvac", "zip_code": "90032", "requesting_user_id": 7})

    assert result == fake_suggestions


def test_get_contractor_suggestions_returns_empty_list_when_api_key_missing():
    with patch("backend.tools.operations_tools.enforce_user_monthly_contractor_search_limit"), \
         patch("backend.tools.operations_tools.search_contractor_directory_cached", side_effect=ValueError("Missing SerpAPI API key.")):
        result = get_contractor_suggestions.invoke({"trade": "hvac", "zip_code": "90032", "requesting_user_id": 7})

    assert result == []


def test_get_contractor_suggestions_returns_empty_list_on_http_error():
    with patch("backend.tools.operations_tools.enforce_user_monthly_contractor_search_limit"), \
         patch("backend.tools.operations_tools.search_contractor_directory_cached", side_effect=httpx.HTTPStatusError("boom", request=None, response=None)):
        result = get_contractor_suggestions.invoke({"trade": "hvac", "zip_code": "90032", "requesting_user_id": 7})

    assert result == []


def test_get_contractor_suggestions_raises_when_monthly_limit_reached(monkeypatch):
    monkeypatch.setattr(settings, "contractor_search_provider", "serpapi")
    with patch(
        "backend.tools.operations_tools.enforce_user_monthly_contractor_search_limit",
        side_effect=ContractorSearchLimitExceeded("limit reached"),
    ):
        with pytest.raises(ContractorSearchLimitExceeded):
            get_contractor_suggestions.invoke(
                {"trade": "hvac", "zip_code": "90032", "requesting_user_id": 7}
            )


def test_get_contractor_suggestions_uses_mock_provider_without_live_calls(monkeypatch):
    monkeypatch.setattr(settings, "contractor_search_provider", "mock")

    with patch(
        "backend.tools.operations_tools.enforce_user_monthly_contractor_search_limit",
        side_effect=AssertionError("quota should not be checked in mock mode"),
    ), patch(
        "backend.tools.operations_tools.record_user_monthly_contractor_search",
        side_effect=AssertionError("quota should not be recorded in mock mode"),
    ), patch(
        "backend.tools.contractor_suggestions.contractor_search.search_serpapi_yelp",
        side_effect=AssertionError("live serp search should not be called in mock mode"),
    ):
        first = get_contractor_suggestions.invoke(
            {"trade": "hvac", "zip_code": "90032", "requesting_user_id": 7}
        )
        second = get_contractor_suggestions.invoke(
            {"trade": "hvac", "zip_code": "90032", "requesting_user_id": 7}
        )

    assert len(first) == len(second) == 3
    assert [item.business_name for item in first] == [item.business_name for item in second]
    assert all(item.provider == "mock_serpapi_yelp" for item in first)
    assert all(item.source_attribution == "Source: mock contractor results for testing." for item in first)
