"""Background document indexing jobs and duplicate detection.

Adds ingestion_jobs, plus content_sha256, uploaded_by_user_id and status on
documents. Existing uploads get their SHA-256 backfilled so a phone re-adding
a PDF that was uploaded on the web is recognised as a duplicate.

Revision ID: 0003_document_jobs
Revises: 0002_reconcile_constraints
Create Date: 2026-10-21
"""
import hashlib

from alembic import op
import sqlalchemy as sa

revision = "0003_document_jobs"
down_revision = "0002_reconcile_constraints"
branch_labels = None
depends_on = None

_NOW = sa.func.now()


def _backfill_sha256() -> None:
    bind = op.get_bind()
    documents = sa.table(
        "documents",
        sa.column("id", sa.Integer()),
        sa.column("file_bytes", sa.LargeBinary()),
        sa.column("content_sha256", sa.String()),
    )
    ids = [
        row.id
        for row in bind.execute(
            sa.select(documents.c.id).where(documents.c.file_bytes.is_not(None), documents.c.content_sha256.is_(None))
        )
    ]
    # One row at a time: stored PDFs can be tens of megabytes each.
    for document_id in ids:
        file_bytes = bind.execute(
            sa.select(documents.c.file_bytes).where(documents.c.id == document_id)
        ).scalar_one()
        bind.execute(
            documents.update()
            .where(documents.c.id == document_id)
            .values(content_sha256=hashlib.sha256(file_bytes).hexdigest())
        )


def upgrade() -> None:
    with op.batch_alter_table("documents") as batch:
        batch.add_column(sa.Column("content_sha256", sa.String(64), nullable=True))
        batch.add_column(sa.Column("uploaded_by_user_id", sa.Integer(), nullable=True))
        batch.add_column(sa.Column("status", sa.String(20), nullable=False, server_default="ready"))
        batch.create_foreign_key(
            "documents_uploaded_by_user_id_fkey",
            "users",
            ["uploaded_by_user_id"],
            ["id"],
            ondelete="SET NULL",
        )
        batch.create_index("ix_documents_household_sha256", ["household_id", "content_sha256"])

    op.create_table(
        "ingestion_jobs",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("household_id", sa.Integer(), sa.ForeignKey("households.id", ondelete="CASCADE"), nullable=False),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("entry_id", sa.String(), nullable=False),
        sa.Column("created_document", sa.Boolean(), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("error_code", sa.String(40), nullable=True),
        sa.Column("error", sa.String(), nullable=True),
        sa.Column("chunks_indexed", sa.Integer(), nullable=True),
        sa.Column("attempts", sa.Integer(), nullable=False),
        sa.Column("idempotency_key", sa.String(100), nullable=True),
        sa.Column("run_after", sa.DateTime(timezone=True), nullable=False, server_default=_NOW),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=_NOW),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=_NOW),
        sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
        sa.UniqueConstraint("user_id", "idempotency_key", name="uq_ingestion_job_idempotency"),
    )
    op.create_index("ix_ingestion_jobs_status_run_after", "ingestion_jobs", ["status", "run_after"])

    _backfill_sha256()


def downgrade() -> None:
    op.drop_index("ix_ingestion_jobs_status_run_after", table_name="ingestion_jobs")
    op.drop_table("ingestion_jobs")
    with op.batch_alter_table("documents") as batch:
        batch.drop_index("ix_documents_household_sha256")
        batch.drop_constraint("documents_uploaded_by_user_id_fkey", type_="foreignkey")
        batch.drop_column("status")
        batch.drop_column("uploaded_by_user_id")
        batch.drop_column("content_sha256")
