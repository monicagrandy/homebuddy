"""Alembic migrations must build exactly the schema the models describe.

The SQLite test always runs. The Postgres tests are opt-in: set PG_TEST_URL to a
server URL whose user can create databases, e.g.
postgresql+psycopg://postgres:postgres@localhost:5432/postgres
"""

import os
import uuid

import pytest
from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
from sqlalchemy import create_engine, inspect, text
from sqlalchemy.engine import make_url

import backend.models  # noqa: F401
from backend.db import Base
from backend.migrations import downgrade_database, upgrade_database

APP_TABLES = set(Base.metadata.tables)


def _schema_diff(engine) -> list:
    with engine.connect() as connection:
        context = MigrationContext.configure(connection, opts={"compare_type": True})
        diff = compare_metadata(context, Base.metadata)
    if engine.dialect.name == "sqlite":
        # SQLite reflects pgvector's VECTOR column as an unknown type.
        diff = [
            item
            for item in diff
            if not (isinstance(item, list) and item[0][0] == "modify_type" and item[0][3] == "embedding")
        ]
    return diff


def test_sqlite_upgrade_matches_models_and_downgrades_cleanly(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'migrations.db'}")

    upgrade_database(engine)

    assert APP_TABLES <= set(inspect(engine).get_table_names())
    assert _schema_diff(engine) == []

    downgrade_database(engine, "base")

    assert not APP_TABLES & set(inspect(engine).get_table_names())


def test_sqlite_answer_details_keep_saved_messages(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'migrations.db'}")
    upgrade_database(engine, "0004_conversations")
    with engine.begin() as connection:
        connection.execute(text("INSERT INTO users (id, cognito_sub, email) VALUES (1, 'sub', 'a@example.com')"))
        connection.execute(text("INSERT INTO households (id, name, zip_code, home_type) VALUES (1, 'Home', '80202', 'condo')"))
        connection.execute(
            text("INSERT INTO conversations (id, household_id, user_id, session_id) VALUES (1, 1, 1, 'thread')")
        )
        connection.execute(
            text(
                "INSERT INTO conversation_messages (conversation_id, household_id, session_id, role, content) "
                "VALUES (1, 1, 'thread', 'assistant', 'Clean the filter.')"
            )
        )

    upgrade_database(engine)

    with engine.connect() as connection:
        row = connection.execute(
            text("SELECT content, sources, urgency_level, should_escalate FROM conversation_messages")
        ).one()
    assert tuple(row) == ("Clean the filter.", None, None, 0)

    downgrade_database(engine, "0004_conversations")
    with engine.connect() as connection:
        assert connection.execute(text("SELECT content FROM conversation_messages")).scalar_one() == "Clean the filter."


@pytest.fixture
def postgres_engine():
    server_url = os.getenv("PG_TEST_URL")
    if not server_url:
        pytest.skip("Set PG_TEST_URL to run Postgres migration tests.")

    database = f"hb_migrations_{uuid.uuid4().hex[:8]}"
    admin = create_engine(server_url, isolation_level="AUTOCOMMIT")
    with admin.connect() as connection:
        connection.execute(text(f'CREATE DATABASE "{database}"'))

    engine = create_engine(make_url(server_url).set(database=database))
    try:
        yield engine
    finally:
        engine.dispose()
        with admin.connect() as connection:
            connection.execute(text(f'DROP DATABASE IF EXISTS "{database}" WITH (FORCE)'))
        admin.dispose()


def _create_legacy_schema(engine) -> None:
    """Recreate what old create_all runs left behind: the baseline tables, but foreign keys
    without ON DELETE rules, no documents unique constraint, and no alembic_version."""
    upgrade_database(engine, "0001_baseline")
    with engine.begin() as connection:
        inspector = inspect(connection)
        for table in inspector.get_table_names():
            for fk in inspector.get_foreign_keys(table):
                columns = ", ".join(fk["constrained_columns"])
                referred = ", ".join(fk["referred_columns"])
                connection.execute(text(f'ALTER TABLE {table} DROP CONSTRAINT "{fk["name"]}"'))
                connection.execute(
                    text(
                        f'ALTER TABLE {table} ADD CONSTRAINT "{fk["name"]}" '
                        f"FOREIGN KEY ({columns}) REFERENCES {fk['referred_table']} ({referred})"
                    )
                )
        connection.execute(text("ALTER TABLE documents DROP CONSTRAINT uq_document_household_entry"))
        connection.execute(text("DROP TABLE alembic_version"))


def _insert_household_with_document(connection, *, entry_id: str = "manual") -> int:
    household_id = connection.execute(
        text("INSERT INTO households (name, zip_code, home_type) VALUES ('Home', '12345', 'house') RETURNING id")
    ).scalar_one()
    connection.execute(
        text(
            "INSERT INTO documents (entry_id, household_id, doc_type, source_name) "
            "VALUES (:entry_id, :household_id, 'manual', 'Manual')"
        ),
        {"entry_id": entry_id, "household_id": household_id},
    )
    return household_id


def test_postgres_fresh_database_matches_models(postgres_engine):
    upgrade_database(postgres_engine)

    assert _schema_diff(postgres_engine) == []

    # Re-running the reconcile step on an already-correct database is a no-op.
    downgrade_database(postgres_engine, "0001_baseline")
    upgrade_database(postgres_engine)
    assert _schema_diff(postgres_engine) == []


def test_postgres_legacy_database_is_adopted_and_cascades(postgres_engine):
    _create_legacy_schema(postgres_engine)
    with postgres_engine.begin() as connection:
        household_id = _insert_household_with_document(connection)
    assert _schema_diff(postgres_engine) != []

    upgrade_database(postgres_engine)

    assert _schema_diff(postgres_engine) == []
    with postgres_engine.begin() as connection:
        connection.execute(text("DELETE FROM households WHERE id = :id"), {"id": household_id})
        remaining = connection.execute(text("SELECT COUNT(*) FROM documents")).scalar_one()
    assert remaining == 0


def test_postgres_legacy_duplicates_block_the_unique_constraint(postgres_engine):
    _create_legacy_schema(postgres_engine)
    with postgres_engine.begin() as connection:
        household_id = _insert_household_with_document(connection)
        connection.execute(
            text(
                "INSERT INTO documents (entry_id, household_id, doc_type, source_name) "
                "VALUES ('manual', :household_id, 'manual', 'Manual copy')"
            ),
            {"household_id": household_id},
        )

    with pytest.raises(RuntimeError, match="duplicate"):
        upgrade_database(postgres_engine)
