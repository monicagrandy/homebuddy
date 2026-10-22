import threading
from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker

from backend.db import Base
from backend.ingestion.loader import DocumentLoadError
from backend.ingestion.pipeline import IngestionCancelled
from backend.models import Document, Household, HouseholdMember, IngestionJob, User
from backend.services.document_jobs import DocumentJobRunner, DocumentJobWorker
from backend.services.documents import allocate_entry_id, slugify
from backend.services.ingestion_service import IngestionError


class FakeClock:
    def __init__(self) -> None:
        self.now = datetime(2026, 10, 21, 12, 0, tzinfo=timezone.utc)

    def __call__(self) -> datetime:
        return self.now


class StubIngestion:
    def __init__(self, behavior=None) -> None:
        self.calls: list[dict] = []
        self.discarded: list[dict] = []
        self.behavior = behavior

    def index(self, **kwargs) -> dict:
        self.calls.append(kwargs)
        if self.behavior is not None:
            return self.behavior(**kwargs)
        return {"entry_id": kwargs["entry_id"], "chunks_indexed": 3}

    def discard(self, **kwargs) -> None:
        self.discarded.append(kwargs)


@pytest.fixture
def session_factory(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'jobs.db'}", connect_args={"check_same_thread": False})
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, autoflush=False, autocommit=False)
    with factory() as session:
        user = User(cognito_sub="sub-1", email="pat@example.com")
        household = Household(name="Home", zip_code="80202", home_type="condo")
        session.add_all([user, household])
        session.flush()
        session.add(HouseholdMember(household_id=household.id, user_id=user.id, role="owner"))
        session.commit()
    yield factory
    engine.dispose()


def _queue(factory, clock, *, entry_id="manual", file_bytes=b"%PDF-1.4 manual", url=None, created_document=True,
           document_status="indexing", job_id="job-1", run_after=None) -> str:
    with factory() as session:
        session.add(
            Document(
                entry_id=entry_id,
                household_id=1,
                doc_type="manual",
                source_name="Manual",
                source_url=url,
                file_bytes=file_bytes,
                status=document_status,
            )
        )
        session.add(
            IngestionJob(
                id=job_id,
                household_id=1,
                user_id=1,
                entry_id=entry_id,
                created_document=created_document,
                status="queued",
                attempts=0,
                run_after=run_after or clock(),
            )
        )
        session.commit()
    return job_id


def _job(factory, job_id="job-1") -> IngestionJob:
    with factory() as session:
        return session.get(IngestionJob, job_id)


def _document(factory, entry_id="manual") -> Document | None:
    with factory() as session:
        return session.scalar(select(Document).where(Document.entry_id == entry_id))


def _runner(factory, clock, ingestion) -> DocumentJobRunner:
    return DocumentJobRunner(
        session_factory=factory,
        ingestion_service_factory=lambda: ingestion,
        clock=clock,
        deletion_check_interval=0,
    )


def test_a_queued_job_indexes_the_document_and_marks_it_ready(session_factory):
    clock, ingestion = FakeClock(), StubIngestion()
    _queue(session_factory, clock)

    assert _runner(session_factory, clock, ingestion).run_next() == "job-1"

    job = _job(session_factory)
    assert (job.status, job.chunks_indexed, job.attempts, job.error_code) == ("succeeded", 3, 1, None)
    assert job.finished_at is not None
    assert _document(session_factory).status == "ready"
    assert ingestion.calls[0]["file_bytes"] == b"%PDF-1.4 manual"
    assert ingestion.calls[0]["url"] is None


def test_url_documents_are_indexed_from_their_url(session_factory):
    clock, ingestion = FakeClock(), StubIngestion()
    _queue(session_factory, clock, file_bytes=None, url="https://example.com/manual.pdf")

    _runner(session_factory, clock, ingestion).run_next()

    assert ingestion.calls[0]["url"] == "https://example.com/manual.pdf"
    assert ingestion.calls[0]["file_bytes"] is None


def test_jobs_that_are_not_due_yet_wait(session_factory):
    clock, ingestion = FakeClock(), StubIngestion()
    _queue(session_factory, clock, run_after=clock() + timedelta(minutes=1))
    runner = _runner(session_factory, clock, ingestion)

    assert runner.run_next() is None
    clock.now += timedelta(minutes=1)
    assert runner.run_next() == "job-1"


@pytest.mark.parametrize("code", ["not_pdf", "no_text", "too_large", "download_failed"])
def test_a_document_that_cannot_be_read_fails_and_is_removed(session_factory, code):
    clock = FakeClock()

    def refuse(**_kwargs):
        raise DocumentLoadError("Could not read it.", code=code)

    ingestion = StubIngestion(refuse)
    _queue(session_factory, clock)

    _runner(session_factory, clock, ingestion).run_next()

    job = _job(session_factory)
    assert (job.status, job.error_code, job.error) == ("failed", code, "Could not read it.")
    assert _document(session_factory) is None
    assert ingestion.discarded == [{"household_id": 1, "entry_id": "manual"}]


def test_a_failed_reindex_keeps_the_existing_document(session_factory):
    clock = FakeClock()

    def refuse(**_kwargs):
        raise DocumentLoadError("Could not read it.", code="not_pdf")

    _queue(session_factory, clock, created_document=False)

    _runner(session_factory, clock, StubIngestion(refuse)).run_next()

    assert _job(session_factory).status == "failed"
    assert _document(session_factory).status == "ready"


def test_temporary_failures_are_retried_with_backoff_then_give_up(session_factory):
    clock = FakeClock()

    def outage(**_kwargs):
        raise IngestionError("Failed to index document.")

    ingestion = StubIngestion(outage)
    _queue(session_factory, clock)
    runner = _runner(session_factory, clock, ingestion)

    runner.run_next()
    job = _job(session_factory)
    assert (job.status, job.attempts, job.error_code) == ("queued", 1, "retrying")
    assert _document(session_factory).status == "indexing"

    assert runner.run_next() is None
    clock.now += timedelta(seconds=30)
    assert runner.run_next() == "job-1"
    assert _job(session_factory).attempts == 2

    clock.now += timedelta(minutes=2)
    runner.run_next()

    job = _job(session_factory)
    assert (job.status, job.attempts, job.error_code) == ("failed", 3, "index_failed")
    assert _document(session_factory) is None
    assert len(ingestion.calls) == 3


def test_a_document_deleted_before_its_job_runs_fails_the_job(session_factory):
    clock, ingestion = FakeClock(), StubIngestion()
    _queue(session_factory, clock)
    with session_factory() as session:
        session.delete(session.scalar(select(Document)))
        session.commit()

    _runner(session_factory, clock, ingestion).run_next()

    assert (_job(session_factory).status, _job(session_factory).error_code) == ("failed", "deleted")
    assert ingestion.calls == []


def test_a_document_deleted_mid_index_stops_the_job(session_factory):
    clock = FakeClock()

    def delete_then_check(**kwargs):
        with session_factory() as session:
            session.delete(session.scalar(select(Document)))
            session.commit()
        if kwargs["should_cancel"]():
            raise IngestionCancelled("cancelled")
        raise AssertionError("should_cancel did not notice the deletion")

    ingestion = StubIngestion(delete_then_check)
    _queue(session_factory, clock)

    _runner(session_factory, clock, ingestion).run_next()

    assert (_job(session_factory).status, _job(session_factory).error_code) == ("failed", "deleted")
    assert ingestion.discarded == [{"household_id": 1, "entry_id": "manual"}]


def test_chunks_written_for_a_document_deleted_meanwhile_are_removed(session_factory):
    clock = FakeClock()

    def index_then_deleted(**kwargs):
        with session_factory() as session:
            session.delete(session.scalar(select(Document)))
            session.commit()
        return {"entry_id": kwargs["entry_id"], "chunks_indexed": 3}

    ingestion = StubIngestion(index_then_deleted)
    _queue(session_factory, clock)

    _runner(session_factory, clock, ingestion).run_next()

    assert (_job(session_factory).status, _job(session_factory).error_code) == ("failed", "deleted")
    assert ingestion.discarded == [{"household_id": 1, "entry_id": "manual"}]


def test_interrupted_jobs_are_requeued_on_startup(session_factory):
    clock = FakeClock()
    _queue(session_factory, clock)
    with session_factory() as session:
        session.get(IngestionJob, "job-1").status = "running"
        session.commit()

    assert _runner(session_factory, clock, StubIngestion()).recover_interrupted() == 1
    assert _job(session_factory).status == "queued"


def test_finished_jobs_are_purged_after_30_days(session_factory):
    clock = FakeClock()
    runner = _runner(session_factory, clock, StubIngestion())
    _queue(session_factory, clock, entry_id="old", job_id="old-job")
    runner.run_next()
    _queue(session_factory, clock, entry_id="waiting", job_id="waiting-job", run_after=clock() + timedelta(days=60))

    clock.now += timedelta(days=29)
    assert runner.purge_finished() == 0
    clock.now += timedelta(days=2)
    assert runner.purge_finished() == 1

    assert _job(session_factory, "old-job") is None
    assert _job(session_factory, "waiting-job").status == "queued"


def test_worker_thread_runs_jobs_when_notified(session_factory):
    clock = FakeClock()
    done = threading.Event()

    def index(**kwargs):
        done.set()
        return {"entry_id": kwargs["entry_id"], "chunks_indexed": 1}

    worker = DocumentJobWorker(_runner(session_factory, clock, StubIngestion(index)), poll_seconds=30)
    worker.start()
    try:
        _queue(session_factory, clock)
        worker.notify()
        assert done.wait(timeout=5)
    finally:
        worker.stop()

    assert _job(session_factory).status == "succeeded"


@pytest.mark.parametrize(
    ("name", "slug"),
    [
        ("Bosch Dishwasher Manual", "bosch-dishwasher-manual"),
        ("  GE  Washer (2024)!  ", "ge-washer-2024"),
        ("***", "document"),
    ],
)
def test_slugs_match_the_web_apps_entry_ids(name, slug):
    assert slugify(name) == slug


def test_entry_ids_get_a_numeric_suffix_when_taken(session_factory):
    with session_factory() as session:
        for entry_id in ("manual", "manual-2", "manual-guide"):
            session.add(Document(entry_id=entry_id, household_id=1, doc_type="manual", source_name=entry_id))
        session.commit()

        assert allocate_entry_id(session, household_id=1, display_name="Manual") == "manual-3"
        assert allocate_entry_id(session, household_id=1, display_name="Warranty") == "warranty"
