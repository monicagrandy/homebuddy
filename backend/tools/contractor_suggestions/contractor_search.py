import re
import time
from dataclasses import dataclass
from datetime import datetime, timezone

import httpx
from sqlalchemy import select

from backend.config import get_logger, settings
from backend.db import SessionLocal
from backend.models import ContractorSearchUsage
from backend.schemas import ContractorSuggestion

logger = get_logger(__name__)

CONTRACTOR_CACHE_TTL_SECONDS = 3600
CONTRACTOR_CACHE_MAX_ENTRIES = 256
MOCK_CONTRACTOR_SEARCH_PROVIDER = "mock"
LIVE_CONTRACTOR_SEARCH_PROVIDER = "serpapi"


@dataclass
class CachedContractorResponse:
    expires_at: float
    payload: dict


class ContractorSearchLimitExceeded(RuntimeError):
    """Raised when a user has exhausted their monthly contractor lookup quota."""


_CONTRACTOR_RESPONSE_CACHE: dict[tuple[str, str, str], CachedContractorResponse] = {}


def _prune_contractor_cache(*, now: float) -> None:
    expired_keys = [
        key
        for key, cached in _CONTRACTOR_RESPONSE_CACHE.items()
        if cached.expires_at <= now
    ]
    for key in expired_keys:
        _CONTRACTOR_RESPONSE_CACHE.pop(key, None)

    if len(_CONTRACTOR_RESPONSE_CACHE) <= CONTRACTOR_CACHE_MAX_ENTRIES:
        return

    oldest_keys = sorted(
        _CONTRACTOR_RESPONSE_CACHE,
        key=lambda key: _CONTRACTOR_RESPONSE_CACHE[key].expires_at,
    )
    for key in oldest_keys[
        : len(_CONTRACTOR_RESPONSE_CACHE) - CONTRACTOR_CACHE_MAX_ENTRIES
    ]:
        _CONTRACTOR_RESPONSE_CACHE.pop(key, None)


def contractor_search_period_start(
    *, now: datetime | None = None,
) -> datetime:
    current = now or datetime.now(timezone.utc)
    current = current.astimezone(timezone.utc)
    return current.replace(day=1, hour=0, minute=0, second=0, microsecond=0)


def _load_monthly_search_usage(
    *,
    user_id: int,
    period_start: datetime,
) -> ContractorSearchUsage | None:
    with SessionLocal() as session:
        return session.scalar(
            select(ContractorSearchUsage).where(
                ContractorSearchUsage.user_id == user_id,
                ContractorSearchUsage.period_start == period_start,
            )
        )


def enforce_user_monthly_contractor_search_limit(
    *,
    user_id: int,
    now: datetime | None = None,
    limit: int | None = None,
) -> None:
    monthly_limit = limit or settings.contractor_search_monthly_limit
    period_start = contractor_search_period_start(now=now)
    usage = _load_monthly_search_usage(user_id=user_id, period_start=period_start)
    searches_used = usage.searches_used if usage else 0
    if searches_used >= monthly_limit:
        month_label = period_start.strftime("%B %Y")
        raise ContractorSearchLimitExceeded(
            f"Contractor lookup skipped: you've reached the {monthly_limit} search limit for {month_label}."
        )


def contractor_search_uses_mock_provider() -> bool:
    return settings.contractor_search_provider == MOCK_CONTRACTOR_SEARCH_PROVIDER


def contractor_search_provider_label() -> str:
    if contractor_search_uses_mock_provider():
        return "mock_serpapi_yelp"
    return "serpapi_yelp"


def contractor_search_source_attribution(provider: str) -> str | None:
    if provider == "serpapi_yelp":
        return "Source: Yelp results via SerpApi."
    if provider == "mock_serpapi_yelp":
        return "Source: mock contractor results for testing."
    return None


def record_user_monthly_contractor_search(
    *,
    user_id: int,
    now: datetime | None = None,
    limit: int | None = None,
) -> None:
    monthly_limit = limit or settings.contractor_search_monthly_limit
    period_start = contractor_search_period_start(now=now)

    with SessionLocal() as session:
        usage = session.scalar(
            select(ContractorSearchUsage).where(
                ContractorSearchUsage.user_id == user_id,
                ContractorSearchUsage.period_start == period_start,
            )
        )
        if usage is None:
            usage = ContractorSearchUsage(
                user_id=user_id,
                period_start=period_start,
                searches_used=0,
            )
            session.add(usage)
            session.flush()

        if usage.searches_used >= monthly_limit:
            month_label = period_start.strftime("%B %Y")
            raise ContractorSearchLimitExceeded(
                f"Contractor lookup skipped: you've reached the {monthly_limit} search limit for {month_label}."
            )

        usage.searches_used += 1
        session.commit()


def build_serpapi_yelp_search_term(trade: str) -> str:
    normalized = normalize_trade(trade)
    trade_queries = {
        "plumber": "plumbers",
        "electrician": "electricians",
        "hvac": "heating & air conditioning/hvac",
        "appliance repair": "appliances & repair",
        "lawn care": "landscaping",
        "tree removal": "tree services",
        "arborist": "tree services",
        "roof repair": "roofing",
        "pest control": "pest control",
        "security/alarm technician": "security systems",
        "locksmith": "locksmiths",
        "fence contractor": "fences & gates",
        "garden service": "gardeners",
        "auto mechanic": "auto repair",
        "marine repair": "boat repair",
        "general contractor": "contractors",
    }
    return trade_queries.get(normalized, trade)


def _mock_business_name(trade: str, index: int) -> str:
    suffixes = ["Home Services", "Pros", "Experts"]
    base = trade.title()
    return f"HomeBuddy {base} {suffixes[index]}"


def search_mock_serpapi_yelp(
    *,
    trade: str,
    location: str,
) -> dict:
    normalized = normalize_trade(trade)
    search_term = build_serpapi_yelp_search_term(trade)
    keywords = expand_trade_keywords(trade)[:4]
    keyword_text = ", ".join(keywords) if keywords else normalized

    organic_results = []
    for index, rating in enumerate((4.9, 4.8, 4.7)):
        organic_results.append(
            {
                "position": index + 1,
                "title": _mock_business_name(trade, index),
                "rating": rating,
                "reviews": 120 - (index * 17),
                "phone": f"555-010{index + 1}",
                "link": f"https://example.com/mock-contractors/{normalized.replace(' ', '-')}/{index + 1}",
                "snippet": (
                    f"{trade.title()} specialists serving {location}. "
                    f"Services include {keyword_text}."
                ),
                "categories": [
                    {
                        "alias": normalized.replace(" ", "-"),
                        "title": trade.title(),
                    },
                    {
                        "alias": search_term.replace(" ", "-").replace("/", "-"),
                        "title": search_term.title(),
                    },
                ],
                "neighborhoods": location,
            }
        )

    return {
        "search_metadata": {
            "id": f"mock-{normalized.replace(' ', '-')}-{location}",
            "status": "Success",
            "mock": True,
        },
        "search_parameters": {
            "engine": "yelp",
            "find_desc": search_term,
            "find_loc": location,
        },
        "organic_results": organic_results,
    }


def search_serpapi_yelp(
    *,
    trade: str,
    location: str,
) -> dict:
    if not settings.serpapi_api_key:
        raise ValueError("Missing SerpAPI API key.")

    params = {
        "engine": "yelp",
        "find_desc": build_serpapi_yelp_search_term(trade),
        "find_loc": location,
        "api_key": settings.serpapi_api_key,
    }

    response = httpx.get(
        settings.serpapi_api_url,
        params=params,
        timeout=30.0,
    )
    response.raise_for_status()
    return response.json()


def search_contractor_directory(
    *,
    trade: str,
    location: str,
) -> dict:
    provider = settings.contractor_search_provider
    if provider == MOCK_CONTRACTOR_SEARCH_PROVIDER:
        return search_mock_serpapi_yelp(trade=trade, location=location)
    if provider == LIVE_CONTRACTOR_SEARCH_PROVIDER:
        return search_serpapi_yelp(trade=trade, location=location)
    raise ValueError(
        "Unsupported contractor search provider. "
        f"Expected one of: {LIVE_CONTRACTOR_SEARCH_PROVIDER}, {MOCK_CONTRACTOR_SEARCH_PROVIDER}."
    )


def search_contractor_directory_cached(
    *,
    trade: str,
    location: str,
    ttl_seconds: int = CONTRACTOR_CACHE_TTL_SECONDS,
) -> dict:
    cache_key = (
        settings.contractor_search_provider,
        normalize_trade(trade),
        location.strip().lower(),
    )
    now = time.time()
    _prune_contractor_cache(now=now)
    cached = _CONTRACTOR_RESPONSE_CACHE.get(cache_key)
    if cached and cached.expires_at > now:
        return cached.payload

    payload = search_contractor_directory(trade=trade, location=location)
    _CONTRACTOR_RESPONSE_CACHE[cache_key] = CachedContractorResponse(
        expires_at=now + ttl_seconds,
        payload=payload,
    )
    _prune_contractor_cache(now=now)
    return payload


def normalize_trade(trade: str) -> str:
    return re.sub(r"\s+", " ", trade.strip().lower())


def expand_trade_keywords(trade: str) -> list[str]:
    normalized = normalize_trade(trade)
    parts = normalized.split()

    keywords = {normalized, *parts}

    trade_synonyms = {
        "plumber": {
            "plumber",
            "plumbing",
            "rooter",
            "drain",
            "sewer",
            "pipe",
            "water heater",
        },
        "electrician": {
            "electrician",
            "electrical",
            "wiring",
            "breaker",
            "panel",
            "outlet",
        },
        "hvac": {
            "hvac",
            "heating",
            "cooling",
            "air conditioning",
            "ac",
            "furnace",
            "ventilation",
        },
        "appliance repair": {
            "appliance",
            "appliance repair",
            "washer",
            "dryer",
            "dishwasher",
            "refrigerator",
            "oven",
        },
        "lawn care": {
            "lawn",
            "lawn care",
            "yard",
            "grass",
            "landscaping",
            "landscape",
            "mowing",
        },
        "tree removal": {
            "tree",
            "tree removal",
            "arborist",
            "stump",
            "stump grinding",
            "trimming",
        },
        "arborist": {
            "arborist",
            "tree",
            "tree service",
            "tree removal",
            "pruning",
            "trimming",
            "stump",
        },
        "roof repair": {"roof", "roofing", "roof repair", "shingle", "gutter"},
        "pest control": {
            "pest",
            "pest control",
            "termite",
            "exterminator",
            "rodent",
        },
        "security/alarm technician": {
            "security",
            "alarm",
            "camera",
            "surveillance",
            "monitoring",
            "access control",
        },
        "locksmith": {"locksmith", "lock", "deadbolt", "rekey", "keypad", "smart lock"},
        "fence contractor": {
            "fence",
            "gate",
            "wood fence",
            "vinyl fence",
            "chain link",
            "wrought iron",
        },
        "garden service": {
            "garden",
            "weeds",
            "weed control",
            "mulch",
            "planting",
            "beds",
            "shrubs",
        },
        "auto mechanic": {
            "auto",
            "car",
            "mechanic",
            "vehicle",
            "tire",
            "brake",
            "battery",
            "engine",
            "check engine",
        },
        "marine repair": {
            "boat",
            "marine",
            "outboard",
            "trailer",
            "propeller",
            "bilge",
            "dock",
        },
        "general contractor": {
            "general contractor",
            "contractor",
            "remodel",
            "renovation",
            "construction",
        },
    }

    if normalized in trade_synonyms:
        keywords.update(trade_synonyms[normalized])

    return sorted(k for k in keywords if k)


def extract_business_text(business: dict) -> str:
    categories = " ".join(
        f"{c.get('alias', '')} {c.get('title', '')}"
        for c in business.get("categories", [])
    )

    attributes = business.get("attributes", {})
    summaries = business.get("summaries", {})
    contextual = business.get("contextual_info", {})

    parts = [
        business.get("name", ""),
        business.get("title", ""),
        categories,
        business.get("snippet", ""),
        business.get("neighborhoods", ""),
        summaries.get("short") or "",
        summaries.get("medium") or "",
        summaries.get("long") or "",
        contextual.get("summary") or "",
        contextual.get("review_snippet") or "",
        attributes.get("AboutThisBizSpecialties") or "",
        attributes.get("AboutThisBizBio") or "",
        (attributes.get("biz_summary") or {}).get("summary") or "",
        (attributes.get("biz_summary_long") or {}).get("summary") or "",
    ]

    return " ".join(part.lower() for part in parts if part).strip()


def is_relevant_business_for_trade(business: dict, trade: str) -> bool:
    business_text = extract_business_text(business)
    keywords = expand_trade_keywords(trade)

    if not business_text or not keywords:
        return False

    positive_match = any(keyword in business_text for keyword in keywords)

    obvious_mismatches = {
        "plumber": {"grass", "lawn", "landscaping", "tree"},
        "electrician": {"pizza", "restaurant", "bar"},
        "hvac": {"pizza", "restaurant", "bar"},
        "auto mechanic": {"lawn", "tree", "pizza", "restaurant"},
        "marine repair": {"lawn", "tree", "pizza", "restaurant"},
    }

    negatives = obvious_mismatches.get(normalize_trade(trade), set())
    negative_match = any(keyword in business_text for keyword in negatives)

    return positive_match and not negative_match


def parse_serpapi_yelp_results_to_contractor_suggestions(
    payload: dict,
    *,
    trade: str,
    provider: str = "serpapi_yelp",
) -> list[ContractorSuggestion]:
    suggestions = []
    attribution = contractor_search_source_attribution(provider)

    for business in payload.get("organic_results", []):
        if is_relevant_business_for_trade(business, trade):
            try:
                reason = (
                    business.get("snippet")
                    or f"Matched Yelp directory result for {trade}."
                )

                suggestions.append(
                    ContractorSuggestion(
                        business_name=business.get("title") or business.get("name", ""),
                        trade=trade,
                        rating=business.get("rating"),
                        review_count=business.get("reviews") or business.get("review_count"),
                        phone=business.get("phone"),
                        url=business.get("link") or business.get("url"),
                        reason_suggested=reason,
                        provider=provider,
                        source_attribution=attribution,
                    )
                )
            except ValueError as exc:
                logger.warning("Malformed business error: %s", exc)

    return [suggestion for suggestion in suggestions if suggestion.business_name]
