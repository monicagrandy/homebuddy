"""Answer details on saved messages: citations and urgency.

Answers were saved as text only, so a thread opened on another device, or after
the app restarted, lost the sources and the safety card the live answer showed.
Existing answers keep no details (their sources were never saved).

Revision ID: 0005_message_answer_details
Revises: 0004_conversations
Create Date: 2026-10-29
"""
from alembic import op
import sqlalchemy as sa

revision = "0005_message_answer_details"
down_revision = "0004_conversations"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("conversation_messages") as batch:
        batch.add_column(sa.Column("sources", sa.JSON(), nullable=True))
        batch.add_column(sa.Column("urgency_level", sa.String(20), nullable=True))
        batch.add_column(sa.Column("should_escalate", sa.Boolean(), nullable=False, server_default=sa.false()))


def downgrade() -> None:
    with op.batch_alter_table("conversation_messages") as batch:
        batch.drop_column("should_escalate")
        batch.drop_column("urgency_level")
        batch.drop_column("sources")
