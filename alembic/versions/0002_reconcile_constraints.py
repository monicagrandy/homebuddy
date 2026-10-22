"""Bring legacy foreign keys and unique constraints in line with the models.

Tables created by older ``create_all`` runs kept their original constraints:
foreign keys without ON DELETE rules (so household deletes fail instead of
cascading) and no unique constraint on documents (household_id, entry_id).
This migration checks each expected constraint and only changes the ones that
differ, so it is a no-op on databases created by 0001.

Revision ID: 0002_reconcile_constraints
Revises: 0001_baseline
Create Date: 2026-10-21
"""
import logging

from alembic import op
import sqlalchemy as sa

revision = "0002_reconcile_constraints"
down_revision = "0001_baseline"
branch_labels = None
depends_on = None

logger = logging.getLogger("alembic.runtime.migration")

# (table, column, referred table, ON DELETE rule)
EXPECTED_FOREIGN_KEYS = (
    ("household_members", "household_id", "households", "CASCADE"),
    ("household_members", "user_id", "users", None),
    ("household_invitations", "household_id", "households", "CASCADE"),
    ("household_invitations", "invited_by_user_id", "users", "CASCADE"),
    ("assets", "household_id", "households", "CASCADE"),
    ("documents", "household_id", "households", "CASCADE"),
    ("documents", "asset_id", "assets", "SET NULL"),
    ("document_chunks", "household_id", "households", "CASCADE"),
    ("document_chunks", "asset_id", "assets", "SET NULL"),
    ("cases", "household_id", "households", "CASCADE"),
    ("cases", "asset_id", "assets", "SET NULL"),
    ("maintenance_tasks", "household_id", "households", "CASCADE"),
    ("maintenance_tasks", "asset_id", "assets", "SET NULL"),
    ("conversation_messages", "household_id", "households", "CASCADE"),
    ("contractor_search_usage", "user_id", "users", "CASCADE"),
)

# (table, constraint name, columns)
EXPECTED_UNIQUE_CONSTRAINTS = (
    ("household_members", "uq_household_user", ("household_id", "user_id")),
    ("household_invitations", "uq_household_invite_token", ("token",)),
    ("documents", "uq_document_household_entry", ("household_id", "entry_id")),
    (
        "document_chunks",
        "uq_document_chunk_entry_household_index",
        ("entry_id", "household_id", "chunk_index"),
    ),
    ("contractor_search_usage", "uq_contractor_search_usage_user_period", ("user_id", "period_start")),
)


def _normalize_rule(rule: str | None) -> str | None:
    if rule is None:
        return None
    rule = rule.upper()
    return None if rule in ("NO ACTION", "RESTRICT") else rule


def _reconcile_foreign_keys(inspector) -> None:
    for table, column, referred, ondelete in EXPECTED_FOREIGN_KEYS:
        matches = [
            fk
            for fk in inspector.get_foreign_keys(table)
            if fk["constrained_columns"] == [column] and fk["referred_table"] == referred
        ]
        if len(matches) == 1 and _normalize_rule(matches[0].get("options", {}).get("ondelete")) == ondelete:
            continue

        for fk in matches:
            logger.info("Dropping foreign key %s on %s.%s", fk["name"], table, column)
            op.drop_constraint(fk["name"], table, type_="foreignkey")
        name = f"{table}_{column}_fkey"
        logger.info("Creating foreign key %s (ON DELETE %s)", name, ondelete or "NO ACTION")
        op.create_foreign_key(name, table, referred, [column], ["id"], ondelete=ondelete)


def _has_unique(inspector, table: str, columns: tuple[str, ...]) -> bool:
    wanted = set(columns)
    for constraint in inspector.get_unique_constraints(table):
        if set(constraint["column_names"]) == wanted:
            return True
    for index in inspector.get_indexes(table):
        if index.get("unique") and set(index["column_names"]) == wanted:
            return True
    return False


def _reconcile_unique_constraints(inspector) -> None:
    bind = op.get_bind()
    for table, name, columns in EXPECTED_UNIQUE_CONSTRAINTS:
        if _has_unique(inspector, table, columns):
            continue

        column_list = ", ".join(columns)
        duplicates = bind.execute(
            sa.text(
                f"SELECT {column_list}, COUNT(*) AS copies FROM {table} "
                f"GROUP BY {column_list} HAVING COUNT(*) > 1 LIMIT 5"
            )
        ).fetchall()
        if duplicates:
            raise RuntimeError(
                f"Cannot add unique constraint {name}: {table} has duplicate ({column_list}) rows, "
                f"for example {[tuple(row) for row in duplicates]}. Remove the duplicates and restart."
            )
        logger.info("Creating unique constraint %s on %s (%s)", name, table, column_list)
        op.create_unique_constraint(name, table, list(columns))


def upgrade() -> None:
    bind = op.get_bind()
    if bind.dialect.name != "postgresql":
        # SQLite databases are created fresh by 0001 (tests and local dev only).
        return

    _reconcile_foreign_keys(sa.inspect(bind))
    _reconcile_unique_constraints(sa.inspect(bind))


def downgrade() -> None:
    # The legacy constraints were never intentional, so there is nothing to restore.
    pass
