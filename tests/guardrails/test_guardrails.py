from backend.guardrails.guardrails import DOCUMENT_REDACTION_ENTITIES, SafetyGuardrail


class StubAnalyzer:
    def __init__(self):
        self.calls: list[dict] = []

    def analyze(self, **kwargs):
        self.calls.append(kwargs)
        return ["fake-result"]


class StubAnonymizedResult:
    def __init__(self, text: str):
        self.text = text


class StubAnonymizer:
    def __init__(self):
        self.calls: list[dict] = []

    def anonymize(self, **kwargs):
        self.calls.append(kwargs)
        return StubAnonymizedResult("redacted text")


def test_anonymize_input_uses_document_redaction_entity_policy():
    analyzer = StubAnalyzer()
    anonymizer = StubAnonymizer()
    guardrail = SafetyGuardrail(analyzer=analyzer, anonymizer=anonymizer)

    result = guardrail.anonymize_input(
        "Reach me at user@example.com, SSN 123-45-6789, account 987654321."
    )

    assert result == {"status": "Success", "text": "redacted text"}
    assert analyzer.calls == [
        {
            "text": "Reach me at user@example.com, SSN 123-45-6789, account 987654321.",
            "language": "en",
            "entities": DOCUMENT_REDACTION_ENTITIES,
        }
    ]
    assert anonymizer.calls == [
        {
            "text": "Reach me at user@example.com, SSN 123-45-6789, account 987654321.",
            "analyzer_results": ["fake-result"],
        }
    ]
