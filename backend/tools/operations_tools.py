"""
Home Operations agent tools
- draft_task => Draft a reminder task for the user
- draft_case => Draft a case for a specific issue
- search_contractors => Search for local contractors to resolve an issue
"""

from __future__ import annotations

import httpx
from langchain_core.tools import tool

from backend.config import get_logger, settings
from backend.schemas import CaseDraft, ContractorSuggestion, TaskDraft
from backend.tools.contractor_suggestions.contractor_search import (
    ContractorSearchLimitExceeded,
    contractor_search_provider_label,
    contractor_search_uses_mock_provider,
    enforce_user_monthly_contractor_search_limit,
    parse_serpapi_yelp_results_to_contractor_suggestions,
    record_user_monthly_contractor_search,
    search_contractor_directory_cached,
)

logger = get_logger(__name__)

@tool
def draft_task(
        title: str,
        notes: str | None = None,
        schedule_hint: str | None = None
    ) -> TaskDraft:
    """
        Given the metadata of a task, format the contents into a draft
    """
    return TaskDraft(
        title = title,
        notes = notes,
        schedule_hint = schedule_hint
    )

@tool
def draft_case(
        title: str,
        summary: str,
        severity: str,
        contractor_trade: str | None = None
    ) -> CaseDraft:
    """
        Given the metadata of a case, format the contents into a draft
    """
    return CaseDraft(
        title = title,
        summary = summary,
        severity = severity,
        contractor_trade = contractor_trade
    )

@tool
def get_contractor_suggestions(
        trade: str,
        zip_code: str | None = None,
        requesting_user_id: int | None = None,
    ) -> list[ContractorSuggestion]:
         """
         Given a trade and optional zip code, search for local contractors and return formatted suggestions.
         If zip_code is omitted, fall back to the active household zip code from graph state.
         """
         effective_zip = (zip_code or "").strip()
         if not effective_zip:
             logger.info("Contractor lookup skipped because no zip code was available for trade=%s", trade)
             return []

         if requesting_user_id is None:
             logger.warning("Contractor lookup skipped because user context was unavailable for trade=%s", trade)
             return []

         try:
             if not contractor_search_uses_mock_provider():
                 enforce_user_monthly_contractor_search_limit(user_id=requesting_user_id)
             response_payload = search_contractor_directory_cached(
                trade=trade,
                location=effective_zip,
            )
             if not contractor_search_uses_mock_provider():
                 record_user_monthly_contractor_search(user_id=requesting_user_id)
             limit = settings.contractor_suggestion_limit

             suggestions = parse_serpapi_yelp_results_to_contractor_suggestions(
                response_payload,
                trade=trade,
                provider=contractor_search_provider_label(),
            )

             return suggestions[:limit]
         except ContractorSearchLimitExceeded:
             raise
         except (ValueError, httpx.HTTPError) as exc:
             logger.warning("Contractor lookup failed for trade=%s zip_code=%s: %s", trade, effective_zip, exc)
             return []
         except Exception as exc:
             logger.exception("Unexpected contractor lookup failure for trade=%s zip_code=%s", trade, effective_zip)
             return []
