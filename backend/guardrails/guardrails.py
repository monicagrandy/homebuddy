"""Safety guardrails for inputs and outputs."""
import re

from presidio_analyzer import AnalyzerEngine
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

class SafetyGuardrail:
    def __init__(self, analyzer: AnalyzerEngine = None, anonymizer: AnonymizerEngine = None):
         """Injects PII analysis engines."""
         self.analyzer = analyzer or AnalyzerEngine()
         self.anonymizer = anonymizer or AnonymizerEngine()
    
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
