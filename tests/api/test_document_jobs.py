"""POST /documents/jobs and GET /documents/jobs/{id}: background uploads from phones."""

import pytest
from fastapi.testclient import TestClient

from backend import api
from backend.auth import AuthenticatedIdentity
from backend.services.document_jobs import DocumentJobRunner

PDF = b"%PDF-1.4\n1 0 obj\n<<>>\nendobj\n"
OTHER_PDF = b"%PDF-1.4\n% a different manual\n"


class StubIngestion:
    def __init__(self) -> None:
        self.calls: list[dict] = []

    def index(self, **kwargs) -> dict:
        self.calls.append(kwargs)
        return {"entry_id": kwargs["entry_id"], "chunks_indexed": 4}

    def discard(self, **_kwargs) -> None:
        return None


@pytest.fixture
def household_id(client: TestClient) -> int:
    client.get("/auth/me")
    return client.post("/households", json={"name": "Home", "zip_code": "80202", "home_type": "condo"}).json()["id"]


@pytest.fixture
def run_jobs(client: TestClient):
    ingestion = StubIngestion()
    runner = DocumentJobRunner(
        session_factory=client.testing_session_local,
        ingestion_service_factory=lambda: ingestion,
        deletion_check_interval=0,
    )

    def run_all() -> StubIngestion:
        while runner.run_next() is not None:
            pass
        return ingestion

    return run_all


def _upload(client, household_id, *, content=PDF, name="Bosch Dishwasher Manual", key=None, url=None):
    data = {"household_id": str(household_id), "display_name": name, "doc_type": "manual"}
    headers = {"Idempotency-Key": key} if key else {}
    if url is not None:
        return client.post("/documents/jobs", data={**data, "url": url}, headers=headers)
    return client.post(
        "/documents/jobs",
        data=data,
        files={"file": ("manual.pdf", content, "application/pdf")},
        headers=headers,
    )


def _documents(client, household_id) -> list[dict]:
    return client.get("/documents", params={"household_id": household_id}).json()


def test_upload_is_queued_then_indexed_in_the_background(client, household_id, run_jobs):
    created = _upload(client, household_id)

    assert created.status_code == 202
    job = created.json()
    assert job == {
        "job_id": job["job_id"],
        "entry_id": "bosch-dishwasher-manual",
        "status": "queued",
        "duplicate_of": None,
        "error_code": None,
        "error": None,
        "chunks_indexed": None,
        "attempts": 0,
    }
    assert [(d["entry_id"], d["status"]) for d in _documents(client, household_id)] == [
        ("bosch-dishwasher-manual", "indexing")
    ]

    ingestion = run_jobs()

    polled = client.get(f"/documents/jobs/{job['job_id']}").json()
    assert (polled["status"], polled["chunks_indexed"], polled["attempts"]) == ("succeeded", 4, 1)
    assert [d["status"] for d in _documents(client, household_id)] == ["ready"]
    assert ingestion.calls[0]["file_bytes"] == PDF


def test_retrying_with_the_same_idempotency_key_returns_the_same_job(client, household_id):
    first = _upload(client, household_id, key="upload-123")
    retry = _upload(client, household_id, key="upload-123")

    assert first.status_code == 202
    assert retry.status_code == 200
    assert retry.json()["job_id"] == first.json()["job_id"]
    assert len(_documents(client, household_id)) == 1


def test_the_same_pdf_from_a_second_device_is_reported_as_a_duplicate(client, household_id):
    first = _upload(client, household_id, key="phone-a")
    second = _upload(client, household_id, key="phone-b", name="Dishwasher manual (copy)")

    assert second.status_code == 200
    assert second.json()["status"] == "duplicate"
    assert second.json()["duplicate_of"] == first.json()["entry_id"]
    assert second.json()["job_id"] is None
    assert len(_documents(client, household_id)) == 1


def test_a_pdf_uploaded_on_the_web_is_a_duplicate_on_the_phone(client, household_id):
    api.app.dependency_overrides[api.get_ingestion_service] = StubIngestion
    client.post(
        "/documents/index",
        data={"household_id": str(household_id), "entry_id": "web-manual", "session_id": "s", "doc_type": "manual"},
        files={"file": ("manual.pdf", PDF, "application/pdf")},
    )

    response = _upload(client, household_id)

    assert response.json()["status"] == "duplicate"
    assert response.json()["duplicate_of"] == "web-manual"


def test_the_same_url_twice_is_a_duplicate(client, household_id):
    first = _upload(client, household_id, url="https://example.com/manual.pdf")
    second = _upload(client, household_id, url="https://example.com/manual.pdf", name="Again")

    assert first.status_code == 202
    assert second.json()["status"] == "duplicate"


def test_different_files_with_the_same_name_get_separate_entry_ids(client, household_id):
    first = _upload(client, household_id, content=PDF, name="Manual")
    second = _upload(client, household_id, content=OTHER_PDF, name="Manual")

    assert (first.json()["entry_id"], second.json()["entry_id"]) == ("manual", "manual-2")


@pytest.mark.parametrize(
    ("kwargs", "status_code", "code"),
    [
        ({"content": b"<html>not a pdf</html>"}, 422, "not_pdf"),
        ({"content": b""}, 422, "empty"),
        ({"url": "ftp://example.com/manual.pdf"}, 422, "invalid_url"),
        ({"url": "not a url"}, 422, "invalid_url"),
    ],
)
def test_bad_uploads_are_rejected_with_a_code(client, household_id, kwargs, status_code, code):
    response = _upload(client, household_id, **kwargs)

    assert response.status_code == status_code
    assert response.json()["detail"]["code"] == code
    assert _documents(client, household_id) == []


def test_files_over_the_size_limit_are_rejected(client, household_id, monkeypatch):
    monkeypatch.setattr(api.settings, "max_upload_bytes", 10)

    response = _upload(client, household_id)

    assert response.status_code == 413
    assert response.json()["detail"]["code"] == "too_large"


def test_exactly_one_of_file_or_url_is_required(client, household_id):
    neither = client.post(
        "/documents/jobs", data={"household_id": str(household_id), "display_name": "x", "doc_type": "manual"}
    )
    both = client.post(
        "/documents/jobs",
        data={"household_id": str(household_id), "display_name": "x", "doc_type": "manual", "url": "https://e.com/a.pdf"},
        files={"file": ("manual.pdf", PDF, "application/pdf")},
    )

    assert neither.status_code == both.status_code == 400


class NoopVectorStore:
    def delete_entry(self, **_kwargs) -> None:
        return None


def test_a_document_deleted_before_indexing_fails_its_job(client, household_id, run_jobs, monkeypatch):
    monkeypatch.setattr(api, "get_vector_store", NoopVectorStore)
    job = _upload(client, household_id).json()
    client.delete(f"/documents/{job['entry_id']}", params={"household_id": household_id})

    run_jobs()

    polled = client.get(f"/documents/jobs/{job['job_id']}").json()
    assert (polled["status"], polled["error_code"]) == ("failed", "deleted")


def test_jobs_are_private_to_the_household(client, household_id):
    job = _upload(client, household_id).json()
    api.app.dependency_overrides[api.get_current_identity] = lambda: AuthenticatedIdentity(
        subject="stranger-sub", email="stranger@example.com"
    )
    client.get("/auth/me")

    assert client.get(f"/documents/jobs/{job['job_id']}").status_code == 404
    assert _upload(client, household_id).status_code == 403


def test_unknown_jobs_are_not_found(client, household_id):
    assert client.get("/documents/jobs/does-not-exist").status_code == 404


def test_uploads_still_index_and_deduplicate_with_encryption_on(client, household_id, run_jobs, monkeypatch):
    from backend.encryption import MARKER, generate_key
    from backend.models import Document

    monkeypatch.setenv("DOCUMENT_ENCRYPTION_KEY", generate_key())
    _upload(client, household_id, key="enc-1")
    duplicate = _upload(client, household_id, key="enc-2")

    ingestion = run_jobs()

    assert duplicate.json()["status"] == "duplicate"
    assert ingestion.calls[0]["file_bytes"] == PDF
    with client.testing_session_local() as session:
        raw = session.execute(api.text("SELECT file_bytes FROM documents")).scalar_one()
        assert bytes(raw).startswith(MARKER)
        assert session.query(Document).one().file_bytes == PDF
