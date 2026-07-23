import pytest

from backend.ingestion.loader import DocumentLoadError
from backend.services.ingestion_service import IngestionError, IngestionService


class StubPipeline:
    def __init__(self):
        self.calls = []

    def ingest_download_from_url(self, household_id: int, entry_id: str, url: str, session_id: str, doc_type: str):
        self.calls.append((household_id, entry_id, url, session_id, doc_type))
        return {"entry_id": entry_id, "chunks_indexed": 3}

    def ingest_upload_from_file(self, household_id: int, entry_id: str, file_bytes: bytes, session_id: str, doc_type: str):
        self.calls.append((household_id, entry_id, file_bytes, session_id, doc_type))
        return {"entry_id": entry_id, "chunks_indexed": 2}


@pytest.mark.parametrize(
    ("kwargs", "message"),
    [
        ({"household_id": 1, "entry_id": "", "session_id": "session-1", "url": "https://example.com/manual.pdf", "doc_type": "manual"}, "Missing entry ID."),
        ({"household_id": 1, "entry_id": "manual-1", "session_id": "", "url": "https://example.com/manual.pdf", "doc_type": "manual"}, "Missing session ID."),
        ({"household_id": 1, "entry_id": "manual-1", "session_id": "session-1", "doc_type": "manual"}, "Missing document content."),
        (
            {
                "household_id": 1,
                "entry_id": "manual-1",
                "session_id": "session-1",
                "url": "https://example.com/manual.pdf",
                "doc_type": "manual",
                "file_bytes": b"pdf",
            },
            "Provide either a URL or a file, not both.",
        ),
    ],
)
def test_index_validates_inputs(kwargs, message):
    service = IngestionService(vector_manager=object(), pipeline=StubPipeline())

    with pytest.raises(ValueError, match=message.replace('.', r'\.')):
        service.index(**kwargs)


def test_index_routes_url_requests_to_pipeline():
    pipeline = StubPipeline()
    service = IngestionService(vector_manager=object(), pipeline=pipeline)

    result = service.index(
        household_id = 1,
        entry_id="manual-1",
        session_id="session-1",
        url="https://example.com/manual.pdf",
        doc_type="manual",
    )

    assert result == {"entry_id": "manual-1", "chunks_indexed": 3}
    assert pipeline.calls == [(1, "manual-1", "https://example.com/manual.pdf", "manual", "session-1")]


def test_index_routes_upload_requests_to_pipeline():
    pipeline = StubPipeline()
    service = IngestionService(vector_manager=object(), pipeline=pipeline)

    result = service.index(
        household_id = 1,
        entry_id="manual-2",
        session_id="session-2",
        file_bytes=b"fake-pdf-bytes",
        doc_type="warranty",
    )

    assert result == {"entry_id": "manual-2", "chunks_indexed": 2}
    assert pipeline.calls == [(1, "manual-2", b"fake-pdf-bytes", "warranty", "session-2")]


def test_index_reraises_document_load_errors():
    class FailingPipeline:
        def ingest_download_from_url(self, household_id: int, entry_id: str, url: str, session_id: str, doc_type: str):
            raise DocumentLoadError("bad pdf")

    service = IngestionService(vector_manager=object(), pipeline=FailingPipeline())

    with pytest.raises(DocumentLoadError, match="bad pdf"):
        service.index(
            household_id = 1,
            entry_id="manual-1",
            session_id="session-1",
            url="https://example.com/manual.pdf",
            doc_type="manual",
        )


def test_index_wraps_unexpected_pipeline_errors():
    class FailingPipeline:
        def ingest_upload_from_file(self, entry_id: str, file_bytes: bytes, session_id: str, doc_type: str):
            raise RuntimeError("boom")

    service = IngestionService(vector_manager=object(), pipeline=FailingPipeline())

    with pytest.raises(IngestionError, match=r"Failed to index document\."):
        service.index(
            household_id = 1,
            entry_id="manual-2",
            session_id="session-2",
            file_bytes=b"fake-pdf-bytes",
            doc_type="manual",
        )
