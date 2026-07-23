import re
import time
from dataclasses import dataclass

import httpx

from backend.config import get_logger, settings
from backend.schemas import ContractorSuggestion

logger = get_logger(__name__)

CONTRACTOR_CACHE_TTL_SECONDS = 3600
CONTRACTOR_CACHE_MAX_ENTRIES = 256

@dataclass
class CachedYelpResponse:
    expires_at: float
    payload: dict

_YELP_RESPONSE_CACHE: dict[tuple[str, str | None], CachedYelpResponse] = {}


def _prune_yelp_cache(*, now: float) -> None:
    expired_keys = [
        key for key, cached in _YELP_RESPONSE_CACHE.items() if cached.expires_at <= now
    ]
    for key in expired_keys:
        _YELP_RESPONSE_CACHE.pop(key, None)

    if len(_YELP_RESPONSE_CACHE) <= CONTRACTOR_CACHE_MAX_ENTRIES:
        return

    oldest_keys = sorted(
        _YELP_RESPONSE_CACHE,
        key=lambda key: _YELP_RESPONSE_CACHE[key].expires_at,
    )
    for key in oldest_keys[: len(_YELP_RESPONSE_CACHE) - CONTRACTOR_CACHE_MAX_ENTRIES]:
        _YELP_RESPONSE_CACHE.pop(key, None)

def search_yelp_ai(query: str, chat_id: str | None = None) -> dict:
    if not settings.yelp_api_key:
        raise ValueError("Missing Yelp API key.")

    payload = {
        "query": query,
        "chat_id": chat_id,
    }

    headers = {
        "Authorization": f"Bearer {settings.yelp_api_key}",
        "Content-Type": "application/json",
    }

    response = httpx.post(
        settings.yelp_api_url,
        json=payload,
        headers=headers,
        timeout=30.0,
    )
    response.raise_for_status()

    data = response.json()

    return data

def search_yelp_ai_cached(
    query: str,
    chat_id: str | None = None,
    *,
    ttl_seconds: int = CONTRACTOR_CACHE_TTL_SECONDS,
) -> dict:
    # Cache only deterministic contractor lookups. Stateful chat threads should always hit Yelp directly.
    cache_key = (query, chat_id)
    now = time.time()
    if chat_id is None:
        _prune_yelp_cache(now=now)
        cached = _YELP_RESPONSE_CACHE.get(cache_key)
        if cached and cached.expires_at > now:
            return cached.payload

    payload = search_yelp_ai(query=query, chat_id=chat_id)

    if chat_id is None:
        _YELP_RESPONSE_CACHE[cache_key] = CachedYelpResponse(
            expires_at=now + ttl_seconds,
            payload=payload,
        )
        _prune_yelp_cache(now=now)
    return payload


def normalize_trade(trade: str) -> str:
    return re.sub(r"\s+", " ", trade.strip().lower())


def expand_trade_keywords(trade: str) -> list[str]:
    normalized = normalize_trade(trade)
    parts = normalized.split()

    keywords = {normalized, *parts}

    trade_synonyms = {
        "plumber": {"plumber", "plumbing", "rooter", "drain", "sewer", "pipe", "water heater"},
        "electrician": {"electrician", "electrical", "wiring", "breaker", "panel", "outlet"},
        "hvac": {"hvac", "heating", "cooling", "air conditioning", "ac", "furnace", "ventilation"},
        "appliance repair": {"appliance", "appliance repair", "washer", "dryer", "dishwasher", "refrigerator", "oven"},
        "lawn care": {"lawn", "lawn care", "yard", "grass", "landscaping", "landscape", "mowing"},
        "tree removal": {"tree", "tree removal", "arborist", "stump", "stump grinding", "trimming"},
        "arborist": {"arborist", "tree", "tree service", "tree removal", "pruning", "trimming", "stump"},
        "roof repair": {"roof", "roofing", "roof repair", "shingle", "gutter"},
        "pest control": {"pest", "pest control", "termite", "exterminator", "rodent"},
        "security/alarm technician": {"security", "alarm", "camera", "surveillance", "monitoring", "access control"},
        "locksmith": {"locksmith", "lock", "deadbolt", "rekey", "keypad", "smart lock"},
        "fence contractor": {"fence", "gate", "wood fence", "vinyl fence", "chain link", "wrought iron"},
        "garden service": {"garden", "weeds", "weed control", "mulch", "planting", "beds", "shrubs"},
        "auto mechanic": {"auto", "car", "mechanic", "vehicle", "tire", "brake", "battery", "engine", "check engine"},
        "marine repair": {"boat", "marine", "outboard", "trailer", "propeller", "bilge", "dock"},
        "general contractor": {"general contractor", "contractor", "remodel", "renovation", "construction"},
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
        categories,
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

    
def parse_yelp_ai_entities_to_contractor_suggestions(
    payload: dict,
    *,
    trade: str,
    provider: str = "yelp_ai",
) -> list[ContractorSuggestion]:
    suggestions = []

    for entity in payload.get("entities", []):
        for business in entity.get("businesses", []):
            if is_relevant_business_for_trade(business, trade):
                try:
                    attributes = business.get("attributes", {})
                    summaries = business.get("summaries", {})
                    contextual = business.get("contextual_info", {})
                    reason = (
                        contextual.get("summary")
                        or summaries.get("short")
                        or (attributes.get("biz_summary") or {}).get("summary")
                        or attributes.get("AboutThisBizSpecialties")
                        or f"Matched Yelp result for {trade}."
                    )

                    suggestions.append(
                        ContractorSuggestion(
                            business_name=business.get("name", ""),
                            trade=trade,
                            rating=business.get("rating"),
                            review_count=business.get("review_count"),
                            phone=business.get("phone"),
                            url=business.get("url"),
                            reason_suggested=reason,
                            provider=provider,
                        )
                    )
                except ValueError as e:
                    logger.warning(f"Malformed business error: {e}")

    return [s for s in suggestions if s.business_name]
