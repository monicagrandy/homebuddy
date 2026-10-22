"""Conversations: one row per chat thread, owned by the user who started it.

Messages were only grouped by (household_id, session_id). This adds a
conversations table so threads can be listed across devices, kept private to
their owner, locked while a question is being answered, and deleted after a
period of inactivity. Existing threads are assigned to their household's owner.

Revision ID: 0004_conversations
Revises: 0003_document_jobs
Create Date: 2026-10-21
"""
from alembic import op
import sqlalchemy as sa

revision = "0004_conversations"
down_revision = "0003_document_jobs"
branch_labels = None
depends_on = None

_NOW = sa.func.now()
_TITLE_LENGTH = 60


def _title(question: str | None) -> str | None:
    if not question:
        return None
    text = " ".join(question.split())
    if len(text) <= _TITLE_LENGTH:
        return text
    return text[:_TITLE_LENGTH].rsplit(" ", 1)[0].rstrip(" ,.;:") + "…"


def _backfill() -> None:
    bind = op.get_bind()
    messages = sa.table(
        "conversation_messages",
        sa.column("id", sa.Integer()),
        sa.column("household_id", sa.Integer()),
        sa.column("session_id", sa.String()),
        sa.column("role", sa.String()),
        sa.column("content", sa.String()),
        sa.column("created_at", sa.DateTime(timezone=True)),
        sa.column("conversation_id", sa.Integer()),
    )
    members = sa.table(
        "household_members",
        sa.column("id", sa.Integer()),
        sa.column("household_id", sa.Integer()),
        sa.column("user_id", sa.Integer()),
        sa.column("role", sa.String()),
    )
    conversations = sa.table(
        "conversations",
        sa.column("id", sa.Integer()),
        sa.column("household_id", sa.Integer()),
        sa.column("user_id", sa.Integer()),
        sa.column("session_id", sa.String()),
        sa.column("title", sa.String()),
        sa.column("created_at", sa.DateTime(timezone=True)),
        sa.column("updated_at", sa.DateTime(timezone=True)),
    )

    # Each household's earliest owner, or its earliest member when it has no owner.
    owners: dict[int, tuple[int, str]] = {}
    for row in bind.execute(
        sa.select(members.c.household_id, members.c.user_id, members.c.role).order_by(members.c.id)
    ):
        current = owners.get(row.household_id)
        if current is None or (row.role == "owner" and current[1] != "owner"):
            owners[row.household_id] = (row.user_id, row.role)

    threads = bind.execute(
        sa.select(
            messages.c.household_id,
            messages.c.session_id,
            sa.func.min(messages.c.created_at).label("first_at"),
            sa.func.max(messages.c.created_at).label("last_at"),
        ).group_by(messages.c.household_id, messages.c.session_id)
    ).fetchall()

    in_thread = lambda thread: sa.and_(  # noqa: E731
        messages.c.household_id == thread.household_id,
        messages.c.session_id == thread.session_id,
    )
    for thread in threads:
        owner = owners.get(thread.household_id)
        if owner is None:
            # Nobody belongs to the household any more, so nobody can read these.
            bind.execute(messages.delete().where(in_thread(thread)))
            continue
        first_question = bind.execute(
            sa.select(messages.c.content)
            .where(in_thread(thread), messages.c.role == "user")
            .order_by(messages.c.id)
            .limit(1)
        ).scalar()
        conversation_id = bind.execute(
            conversations.insert()
            .values(
                household_id=thread.household_id,
                user_id=owner[0],
                session_id=thread.session_id,
                title=_title(first_question),
                created_at=thread.first_at,
                updated_at=thread.last_at,
            )
            .returning(conversations.c.id)
        ).scalar_one()
        bind.execute(messages.update().where(in_thread(thread)).values(conversation_id=conversation_id))


def upgrade() -> None:
    op.create_table(
        "conversations",
        sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
        sa.Column("household_id", sa.Integer(), sa.ForeignKey("households.id", ondelete="CASCADE"), nullable=False),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("session_id", sa.String(255), nullable=False),
        sa.Column("title", sa.String(120), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=_NOW),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=_NOW),
        sa.Column("active_query_started_at", sa.DateTime(timezone=True), nullable=True),
        sa.UniqueConstraint("household_id", "session_id", name="uq_conversation_household_session"),
    )
    op.create_index("ix_conversations_user_updated", "conversations", ["user_id", "updated_at"])

    with op.batch_alter_table("conversation_messages") as batch:
        batch.add_column(sa.Column("conversation_id", sa.Integer(), nullable=True))

    _backfill()

    with op.batch_alter_table("conversation_messages") as batch:
        batch.alter_column("conversation_id", existing_type=sa.Integer(), nullable=False)
        batch.create_foreign_key(
            "conversation_messages_conversation_id_fkey",
            "conversations",
            ["conversation_id"],
            ["id"],
            ondelete="CASCADE",
        )
        batch.create_index("ix_conversation_messages_conversation", ["conversation_id", "id"])


def downgrade() -> None:
    with op.batch_alter_table("conversation_messages") as batch:
        batch.drop_index("ix_conversation_messages_conversation")
        batch.drop_constraint("conversation_messages_conversation_id_fkey", type_="foreignkey")
        batch.drop_column("conversation_id")
    op.drop_index("ix_conversations_user_updated", table_name="conversations")
    op.drop_table("conversations")
