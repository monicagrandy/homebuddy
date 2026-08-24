from backend.ingestion.pipeline import IngestionPipeline


class StubLoader:
    def __init__(self, pages: list[str]):
        self.pages = pages

    def load_pages_from_upload(self, file_bytes: bytes) -> list[str]:
        return self.pages

    def load_pages_from_url(self, url: str) -> list[str]:
        return self.pages


class StubVectorStore:
    def __init__(self):
        self.calls: list[dict] = []

    def upsert_chunks(self, **kwargs) -> dict:
        self.calls.append(kwargs)
        return {
            "entry_id": kwargs["entry_id"],
            "household_id": kwargs["household_id"],
            "doc_type": kwargs["doc_type"],
            "chunks_indexed": len(kwargs["chunks"]),
        }


class StubGuardrail:
    def anonymize_input(self, text: str) -> dict:
        return {
            "status": "Success",
            "text": (
                text.replace("123-45-6789", "[US_SSN]")
                .replace("987654321", "[US_BANK_NUMBER]")
                .replace("4111 1111 1111 1111", "[CREDIT_CARD]")
                .replace("user@example.com", "[EMAIL_ADDRESS]")
            ),
        }


def test_ingest_upload_redacts_extracted_text_before_indexing():
    pipeline = IngestionPipeline(
        loader=StubLoader(
            [
                "Customer SSN 123-45-6789 and email user@example.com",
                "Card 4111 1111 1111 1111 appears on this page",
            ]
        ),
        vector_manager=StubVectorStore(),
        safety_guardrail=StubGuardrail(),
        chunk_size=500,
    )

    result = pipeline.ingest_upload_from_file(
        household_id=4,
        entry_id="sensitive-doc",
        file_bytes=b"fake-pdf",
        doc_type="warranty",
        session_id="demo-session",
    )

    assert result["chunks_indexed"] == 2
    chunks = pipeline.vector_manager.calls[0]["chunks"]
    assert chunks[0]["text"] == "Customer SSN [US_SSN] and email [EMAIL_ADDRESS]"
    assert chunks[1]["text"] == "Card [CREDIT_CARD] appears on this page"


def test_ingest_download_redacts_extracted_text_before_indexing():
    pipeline = IngestionPipeline(
        loader=StubLoader(["SSN 123-45-6789 from remote PDF"]),
        vector_manager=StubVectorStore(),
        safety_guardrail=StubGuardrail(),
        chunk_size=500,
    )

    result = pipeline.ingest_download_from_url(
        household_id=7,
        entry_id="remote-doc",
        url="https://example.com/remote.pdf",
        doc_type="manual",
        session_id="demo-session",
    )

    assert result["chunks_indexed"] == 1
    chunks = pipeline.vector_manager.calls[0]["chunks"]
    assert chunks[0]["text"] == "SSN [US_SSN] from remote PDF"


def test_ingest_upload_redacts_high_risk_personal_identifiers_but_keeps_business_contact_details():
    pipeline = IngestionPipeline(
        loader=StubLoader(
            [
                (
                    "Primary insured: Monica Grandy. Personal email user@example.com. "
                    "SSN 123-45-6789. Bank account 987654321. Card 4111 1111 1111 1111. "
                    "Call Acme Home Insurance at (800) 555-1212 or visit 200 Market Street, Los Angeles, CA."
                )
            ]
        ),
        vector_manager=StubVectorStore(),
        safety_guardrail=StubGuardrail(),
        chunk_size=1000,
    )

    pipeline.ingest_upload_from_file(
        household_id=9,
        entry_id="insurance-doc",
        file_bytes=b"fake-pdf",
        doc_type="warranty",
        session_id="demo-session",
    )

    chunk_text = pipeline.vector_manager.calls[0]["chunks"][0]["text"]
    assert "[EMAIL_ADDRESS]" in chunk_text
    assert "[US_SSN]" in chunk_text
    assert "[US_BANK_NUMBER]" in chunk_text
    assert "[CREDIT_CARD]" in chunk_text
    assert "Acme Home Insurance" in chunk_text
    assert "(800) 555-1212" in chunk_text
    assert "200 Market Street, Los Angeles, CA" in chunk_text
