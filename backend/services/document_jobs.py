"""Background indexing for documents added through POST /documents/jobs.

The upload request only stores the file and queues a job, so a phone can
background the app or lose its connection without losing the work. Jobs are
claimed with SELECT ... FOR UPDATE SKIP LOCKED, so more worker processes can
be added later without two of them running the same job.
"""

import threading
import time
from collections.abc import Callable
from datetime import datetime, timedelta, timezone

from sqlalchemy import delete, select, update
from sqlalchemy.orm import Session, sessionmaker

from backend.config import get_logger
from backend.ingestion.loader import DocumentLoadError
from backend.ingestion.pipeline import IngestionCancelled
from backend.models import Document, IngestionJob

logger = get_logger(__name__)

QUEUED = "queued"
RUNNING = "running"
SUCCEEDED = "succeeded"
FAILED = "failed"

MAX_ATTEMPTS = 3
# Wait before attempt 2 and attempt 3 after a temporary failure (embedding outage, rate limit).
RETRY_DELAYS = (timedelta(seconds=30), timedelta(minutes=2))
FINISHED_JOB_RETENTION = timedelta(days=30)


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class _DeletionCheck:
    """`should_cancel` for the pipeline: true once the document has been deleted (e.g. from another device)."""

    def __init__(self, session_factory: sessionmaker, *, household_id: int, entry_id: str, interval: float) -> None:
        self._session_factory = session_factory
        self._household_id = household_id
        self._entry_id = entry_id
        self._interval = interval
        self._checked_at = float("-inf")
        self._deleted = False

    def __call__(self) -> bool:
        if self._deleted or time.monotonic() - self._checked_at < self._interval:
            return self._deleted
        self._checked_at = time.monotonic()
        with self._session_factory() as session:
            self._deleted = _find_document(session, self._household_id, self._entry_id) is None
        return self._deleted


def _find_document(session: Session, household_id: int, entry_id: str) -> Document | None:
    return session.scalar(
        select(Document).where(Document.household_id == household_id, Document.entry_id == entry_id)
    )


class DocumentJobRunner:
    def __init__(
        self,
        *,
        session_factory: sessionmaker,
        ingestion_service_factory: Callable,
        clock: Callable[[], datetime] = utc_now,
        deletion_check_interval: float = 2.0,
    ) -> None:
        self._session_factory = session_factory
        self._ingestion_service_factory = ingestion_service_factory
        self._clock = clock
        self._deletion_check_interval = deletion_check_interval

    def run_next(self) -> str | None:
        """Claim and run one job that is due. Returns its id, or None when nothing is due."""
        job_id = self._claim()
        if job_id is not None:
            self._run(job_id)
        return job_id

    def recover_interrupted(self) -> int:
        """Requeue jobs left running by a crash or restart. Call once, before any worker starts."""
        with self._session_factory() as session:
            result = session.execute(
                update(IngestionJob).where(IngestionJob.status == RUNNING).values(status=QUEUED, run_after=self._clock()),
                execution_options={"synchronize_session": False},
            )
            session.commit()
            return result.rowcount or 0

    def purge_finished(self, older_than: timedelta = FINISHED_JOB_RETENTION) -> int:
        with self._session_factory() as session:
            result = session.execute(
                delete(IngestionJob).where(
                    IngestionJob.status.in_((SUCCEEDED, FAILED)),
                    IngestionJob.finished_at < self._clock() - older_than,
                ),
                execution_options={"synchronize_session": False},
            )
            session.commit()
            return result.rowcount or 0

    def _claim(self) -> str | None:
        with self._session_factory() as session:
            job = session.scalar(
                select(IngestionJob)
                .where(IngestionJob.status == QUEUED, IngestionJob.run_after <= self._clock())
                .order_by(IngestionJob.created_at, IngestionJob.id)
                .limit(1)
                .with_for_update(skip_locked=True)
            )
            if job is None:
                return None
            job.status = RUNNING
            job.attempts += 1
            session.commit()
            return job.id

    def _run(self, job_id: str) -> None:
        with self._session_factory() as session:
            job = session.get(IngestionJob, job_id)
            document = _find_document(session, job.household_id, job.entry_id)
            if document is None:
                self._finish_failed(session, job, code="deleted", message="The document was deleted before it was indexed.")
                return

            service = self._ingestion_service_factory()
            use_file = document.file_bytes is not None
            try:
                result = service.index(
                    household_id=job.household_id,
                    entry_id=job.entry_id,
                    session_id=f"job-{job.id}",
                    doc_type=document.doc_type,
                    url=None if use_file else document.source_url,
                    file_bytes=document.file_bytes if use_file else None,
                    should_cancel=_DeletionCheck(
                        self._session_factory,
                        household_id=job.household_id,
                        entry_id=job.entry_id,
                        interval=self._deletion_check_interval,
                    ),
                )
            except IngestionCancelled:
                service.discard(household_id=job.household_id, entry_id=job.entry_id)
                self._finish_failed(session, job, code="deleted", message="The document was deleted while it was being indexed.")
                return
            except DocumentLoadError as exc:
                self._finish_failed(session, job, code=exc.code, message=str(exc), cleanup=service)
                return
            except ValueError as exc:
                self._finish_failed(session, job, code="invalid", message=str(exc), cleanup=service)
                return
            except Exception as exc:  # IngestionError and anything unexpected: worth another try
                logger.exception("Indexing job %s failed (attempt %s)", job.id, job.attempts)
                self._retry_or_fail(session, job, exc, cleanup=service)
                return

            # Deleted while the chunks were being written: don't leave them behind.
            session.expire_all()
            document = _find_document(session, job.household_id, job.entry_id)
            if document is None:
                service.discard(household_id=job.household_id, entry_id=job.entry_id)
                self._finish_failed(session, job, code="deleted", message="The document was deleted while it was being indexed.")
                return

            document.status = "ready"
            job.status = SUCCEEDED
            job.chunks_indexed = result.get("chunks_indexed")
            job.error_code = None
            job.error = None
            job.finished_at = self._clock()
            session.commit()

    def _retry_or_fail(self, session: Session, job: IngestionJob, exc: Exception, *, cleanup) -> None:
        if job.attempts < MAX_ATTEMPTS:
            job.status = QUEUED
            job.run_after = self._clock() + RETRY_DELAYS[job.attempts - 1]
            job.error_code = "retrying"
            job.error = "Indexing hit a temporary problem and will be retried."
            session.commit()
            return
        self._finish_failed(
            session,
            job,
            code="index_failed",
            message="The document could not be indexed. Please try again later.",
            cleanup=cleanup,
        )

    def _finish_failed(self, session: Session, job: IngestionJob, *, code: str, message: str, cleanup=None) -> None:
        document = _find_document(session, job.household_id, job.entry_id)
        if document is not None:
            if job.created_document:
                # A new upload that never became usable is removed, so lists don't fill with broken entries.
                if cleanup is not None:
                    cleanup.discard(household_id=job.household_id, entry_id=job.entry_id)
                session.delete(document)
            else:
                document.status = "ready"
        job.status = FAILED
        job.error_code = code
        job.error = message
        job.finished_at = self._clock()
        session.commit()


class DocumentJobWorker:
    """Runs jobs in a background thread; `notify()` wakes it as soon as a job is queued.

    Old finished jobs are removed by the daily MaintenanceWorker (runner.purge_finished).
    """

    def __init__(self, runner: DocumentJobRunner, *, poll_seconds: float = 5.0) -> None:
        self._runner = runner
        self._poll_seconds = poll_seconds
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        requeued = self._runner.recover_interrupted()
        if requeued:
            logger.info("Requeued %s indexing jobs interrupted by a restart", requeued)
        self._thread = threading.Thread(target=self._loop, name="document-jobs", daemon=True)
        self._thread.start()

    def stop(self, timeout: float = 5.0) -> None:
        self._stop.set()
        self._wake.set()
        if self._thread is not None:
            self._thread.join(timeout)

    def notify(self) -> None:
        self._wake.set()

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                if self._runner.run_next() is not None:
                    continue
            except Exception:
                logger.exception("Document job worker loop failed")
            self._wake.wait(self._poll_seconds)
            self._wake.clear()
