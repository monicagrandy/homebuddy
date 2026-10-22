"""Baseline schema as of the move to Alembic.

On an empty database this creates every table. Databases built earlier with
``Base.metadata.create_all`` already have the tables, so existing tables are
left alone and only missing columns are added (as nullable, because existing
rows can't be backfilled safely). 0002 then brings foreign keys and unique
constraints in line with the models.

Revision ID: 0001_baseline
Revises:
Create Date: 2026-10-21
"""
import logging

from alembic import op
import sqlalchemy as sa

from backend.config import settings

try:
    from pgvector.sqlalchemy import Vector
except Exception:  # pragma: no cover - pgvector is a hard dependency in production
    Vector = None

revision = "0001_baseline"
down_revision = None
branch_labels = None
depends_on = None

logger = logging.getLogger("alembic.runtime.migration")

_NOW = sa.func.now()


def _created_at() -> sa.Column:
    return sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=_NOW)


def _embedding_type():
    if Vector is None:
        return sa.Text()
    return Vector(settings.embedding_dimensions)


def _ensure_table(name: str, columns: list[sa.Column], *constraints) -> None:
    inspector = sa.inspect(op.get_bind())
    if not inspector.has_table(name):
        op.create_table(name, *columns, *constraints)
        return

    existing = {column["name"] for column in inspector.get_columns(name)}
    for column in columns:
        if column.name in existing:
            continue
        if column.primary_key:
            raise RuntimeError(f"Table {name} exists without its primary key column {column.name}.")
        logger.warning("Adding missing column %s.%s as nullable", name, column.name)
        column.nullable = True
        op.add_column(name, column)


def upgrade() -> None:
    if op.get_bind().dialect.name == "postgresql":
        op.execute("CREATE EXTENSION IF NOT EXISTS vector")

    _ensure_table(
        "users",
        [
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column("cognito_sub", sa.String(255), nullable=False, unique=True),
            sa.Column("email", sa.String(255), nullable=False, unique=True),
            sa.Column("display_name", sa.String(255), nullable=True),
            _created_at(),
        ],
    )
    _ensure_table(
        "households",
        [
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column("name", sa.String(50), nullable=False),
            sa.Column("zip_code", sa.String(), nullable=False),
            sa.Column("home_type", sa.String(), nullable=False),
        ],
    )
    _ensure_table(
        "household_members",
        [
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column(
                "household_id",
                sa.Integer(),
                sa.ForeignKey("households.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
            sa.Column("role", sa.String(20), nullable=False),
            _created_at(),
        ],
        sa.UniqueConstraint("household_id", "user_id", name="uq_household_user"),
    )
    _ensure_table(
        "household_invitations",
        [
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column(
                "household_id",
                sa.Integer(),
                sa.ForeignKey("households.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column("email", sa.String(255), nullable=False),
            sa.Column("role", sa.String(20), nullable=False),
            sa.Column("status", sa.String(20), nullable=False),
            sa.Column("token", sa.String(64), nullable=False),
            sa.Column(
                "invited_by_user_id",
                sa.Integer(),
                sa.ForeignKey("users.id", ondelete="CASCADE"),
                nullable=False,
            ),
            _created_at(),
            sa.Column("accepted_at", sa.DateTime(timezone=True), nullable=True),
        ],
        sa.UniqueConstraint("token", name="uq_household_invite_token"),
    )
    _ensure_table(
        "assets",
        [
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column(
                "household_id",
                sa.Integer(),
                sa.ForeignKey("households.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column("name", sa.String(50), nullable=False),
            sa.Column("brand", sa.String(), nullable=True),
            sa.Column("model_number", sa.String(), nullable=True),
            sa.Column("room", sa.String(), nullable=True),
            sa.Column("install_date", sa.DateTime(timezone=True), nullable=True),
            sa.Column("warranty_end_date", sa.DateTime(timezone=True), nullable=True),
        ],
    )
    _ensure_table(
        "documents",
        [
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column("entry_id", sa.String(), nullable=False),
            sa.Column(
                "household_id",
                sa.Integer(),
                sa.ForeignKey("households.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column(
                "asset_id",
                sa.Integer(),
                sa.ForeignKey("assets.id", ondelete="SET NULL"),
                nullable=True,
            ),
            sa.Column("doc_type", sa.String(), nullable=False),
            sa.Column("source_name", sa.String(), nullable=False),
            sa.Column("source_url", sa.String(), nullable=True),
            sa.Column("file_bytes", sa.LargeBinary(), nullable=True),
            sa.Column("uploaded_at", sa.DateTime(timezone=True), nullable=True, server_default=_NOW),
            sa.Column("storage_key", sa.String(), nullable=True),
        ],
        sa.UniqueConstraint("household_id", "entry_id", name="uq_document_household_entry"),
    )
    _ensure_table(
        "document_chunks",
        [
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column(
                "household_id",
                sa.Integer(),
                sa.ForeignKey("households.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column("entry_id", sa.String(), nullable=False),
            sa.Column("session_id", sa.String(), nullable=True),
            sa.Column(
                "asset_id",
                sa.Integer(),
                sa.ForeignKey("assets.id", ondelete="SET NULL"),
                nullable=True,
            ),
            sa.Column("doc_type", sa.String(), nullable=False),
            sa.Column("chunk_index", sa.Integer(), nullable=False),
            sa.Column("source", sa.String(), nullable=False),
            sa.Column("page", sa.Integer(), nullable=True),
            sa.Column("text", sa.Text(), nullable=False),
            sa.Column("embedding", _embedding_type(), nullable=True),
            _created_at(),
        ],
        sa.UniqueConstraint(
            "entry_id",
            "household_id",
            "chunk_index",
            name="uq_document_chunk_entry_household_index",
        ),
    )
    _ensure_table(
        "cases",
        [
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column(
                "household_id",
                sa.Integer(),
                sa.ForeignKey("households.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column(
                "asset_id",
                sa.Integer(),
                sa.ForeignKey("assets.id", ondelete="SET NULL"),
                nullable=True,
            ),
            sa.Column("title", sa.String(), nullable=False),
            sa.Column("summary", sa.String(), nullable=False),
            sa.Column("severity", sa.String(), nullable=False),
            sa.Column("status", sa.String(), nullable=False),
            sa.Column("contractor_trade", sa.String(), nullable=False),
        ],
    )
    _ensure_table(
        "maintenance_tasks",
        [
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column(
                "household_id",
                sa.Integer(),
                sa.ForeignKey("households.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column(
                "asset_id",
                sa.Integer(),
                sa.ForeignKey("assets.id", ondelete="SET NULL"),
                nullable=True,
            ),
            sa.Column("title", sa.String(), nullable=False),
            sa.Column("due_date", sa.DateTime(timezone=True), nullable=True),
            sa.Column("status", sa.String(), nullable=False),
            sa.Column("notes", sa.String(), nullable=True),
        ],
    )
    _ensure_table(
        "conversation_messages",
        [
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column(
                "household_id",
                sa.Integer(),
                sa.ForeignKey("households.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column("session_id", sa.String(255), nullable=False),
            sa.Column("role", sa.String(20), nullable=False),
            sa.Column("content", sa.String(), nullable=False),
            _created_at(),
        ],
    )
    _ensure_table(
        "contractor_search_usage",
        [
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column(
                "user_id",
                sa.Integer(),
                sa.ForeignKey("users.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column("period_start", sa.DateTime(timezone=True), nullable=False),
            sa.Column("searches_used", sa.Integer(), nullable=False),
            _created_at(),
            sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=_NOW),
        ],
        sa.UniqueConstraint("user_id", "period_start", name="uq_contractor_search_usage_user_period"),
    )


def downgrade() -> None:
    # Only meant for throwaway development databases.
    for table in (
        "contractor_search_usage",
        "conversation_messages",
        "maintenance_tasks",
        "cases",
        "document_chunks",
        "documents",
        "assets",
        "household_invitations",
        "household_members",
        "households",
        "users",
    ):
        op.drop_table(table)
