"""Safety guardrails for inputs and outputs."""
import importlib.util
import re

from presidio_analyzer import AnalyzerEngine
from presidio_analyzer.nlp_engine import SpacyNlpEngine
from presidio_anonymizer import AnonymizerEngine
from backend.config import get_logger, settings

logger = get_logger(__name__)

ABUSIVE_FALLBACK_PATTERNS = [
    re.compile(r"\bkill yourself\b", re.IGNORECASE),
    re.compile(r"\bgo kill yourself\b", re.IGNORECASE),
    re.compile(r"\bi(?:'| a)?m going to kill you\b", re.IGNORECASE),
    re.compile(r"\bi will kill you\b", re.IGNORECASE),
    re.compile(r"\bhow do i make a bomb\b", re.IGNORECASE),
    re.compile(r"\bhow to build a bomb\b", re.IGNORECASE),
    re.compile(r"\bhow do i bypass .* safety\b", re.IGNORECASE),
    re.compile(r"\bbypass .* safety interlock\b", re.IGNORECASE),
]

# --- Custom Exception Boundaries ---
class SafetyBlockError(Exception):
    """Raised when an input or output fails toxicity checks."""
    pass


def _is_model_available(model_name: str) -> bool:
    return bool(model_name) and importlib.util.find_spec(model_name) is not None


def _resolve_spacy_model() -> str:
    preferred = settings.presidio_spacy_model.strip() or "en_core_web_sm"
    for candidate in (preferred, "en_core_web_sm", "en_core_web_md", "en_core_web_lg"):
        if _is_model_available(candidate):
            if candidate != preferred:
                logger.warning(
                    "Configured Presidio spaCy model %s is not installed; falling back to %s.",
                    preferred,
                    candidate,
                )
            return candidate
    return preferred


def _build_analyzer_engine() -> AnalyzerEngine:
    model_name = _resolve_spacy_model()
    logger.info("Initializing Presidio AnalyzerEngine with spaCy model %s", model_name)
    nlp_engine = SpacyNlpEngine(
        models=[{"lang_code": "en", "model_name": model_name}],
    )
    return AnalyzerEngine(nlp_engine=nlp_engine)


class SafetyGuardrail:
    def __init__(self, analyzer: AnalyzerEngine = None, anonymizer: AnonymizerEngine = None):
         """Injects PII analysis engines."""
         self.analyzer = analyzer or _build_analyzer_engine()
         self.anonymizer = anonymizer or AnonymizerEngine()

    def warm_up(self) -> None:
        # Touch the NLP path once during startup so the first real user query
        # doesn't pay the full model-load cost.
        self.analyzer.analyze(
            text="Warm up HomeBuddy with warmup@example.com",
            language="en",
            entities=["EMAIL_ADDRESS"],
        )
    
    def anonymize_input(self, text: str) -> dict:
        """Detects and redacts PII like names, phone numbers, addresses, and emails.
        
        Gracefully returns the original text if PII analysis fails.
        """
        try:
            results = self.analyzer.analyze(
                text=text,
                language="en",
                entities=["EMAIL_ADDRESS", "CREDIT_CARD", "US_BANK_NUMBER", "US_SSN"],
            )
            anonymized_result = self.anonymizer.anonymize(text=text, analyzer_results=results)
            return {"status": "Success", "text": anonymized_result.text}
        except Exception as e:
            logger.error(f"PII Anonymization failed: {e}. Proceeding with raw text.")
            return {"status": "Error", "text": text}
    
    def check_toxicity(self, text: str, *, fail_open_on_error: bool = False) -> bool:
        """Checks input/output toxicity, using a narrow lexical fallback if moderation is unavailable."""
        try:
            response = settings.openai_client.moderations.create(
                model=settings.moderation_model,
                input=text,
            )
            return response.results[0].flagged
        except Exception as e:
            logger.warning(
                "OpenAI Moderation API query failed: %s. %s",
                e,
                "Failing open." if fail_open_on_error else "Falling back to local phrase filters.",
            )

        if fail_open_on_error:
            return False

        return any(pattern.search(text) for pattern in ABUSIVE_FALLBACK_PATTERNS)
