from datetime import datetime, timedelta, timezone

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from backend.db import Base
from backend.models import Conversation, ConversationMessage, Household, HouseholdMember, User
from backend.services import conversations as conversation_store
from backend.services.maintenance import MaintenanceWorker

NOW = datetime(2026, 10, 21, 12, 0, tzinfo=timezone.utc)


@pytest.fixture
def session(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'conversations.db'}")
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, autoflush=False, autocommit=False)
    with factory() as session:
        user = User(cognito_sub="sub-1", email="pat@example.com")
        household = Household(name="Home", zip_code="80202", home_type="condo")
        session.add_all([user, household])
        session.flush()
        session.add(HouseholdMember(household_id=household.id, user_id=user.id, role="owner"))
        session.commit()
        yield session
    engine.dispose()


def _thread(session, session_id: str, *, idle: timedelta, locked: bool = False) -> Conversation:
    last_activity = NOW - idle
    conversation = Conversation(
        household_id=1,
        user_id=1,
        session_id=session_id,
        created_at=last_activity,
        updated_at=last_activity,
        active_query_started_at=last_activity if locked else None,
    )
    session.add(conversation)
    session.flush()
    session.add(
        ConversationMessage(
            conversation_id=conversation.id, household_id=1, session_id=session_id, role="user", content="Hi"
        )
    )
    session.commit()
    return conversation


@pytest.mark.parametrize(
    ("question", "title"),
    [
        ("Why is my dishwasher not draining?", "Why is my dishwasher not draining?"),
        ("  How   do I\nreset it?  ", "How do I reset it?"),
        ("x" * 80, "x" * 60 + "…"),
        ("My furnace makes a loud banging noise every time it turns on, what is it", "My furnace makes a loud banging noise every time it turns…"),
        ("", None),
        (None, None),
    ],
)
def test_titles_are_short_single_line_versions_of_the_first_question(question, title):
    assert conversation_store.title_from(question) == title


def test_inactive_conversations_are_purged_with_their_messages(session):
    _thread(session, "idle-91-days", idle=timedelta(days=91))
    _thread(session, "idle-89-days", idle=timedelta(days=89))

    removed = conversation_store.purge_inactive(session, older_than=timedelta(days=90), now=NOW)

    assert removed == 1
    assert [c.session_id for c in session.query(Conversation)] == ["idle-89-days"]
    assert [m.session_id for m in session.query(ConversationMessage)] == ["idle-89-days"]


def test_a_conversation_being_answered_is_never_purged(session):
    _thread(session, "old-but-busy", idle=timedelta(days=200), locked=True)

    assert conversation_store.purge_inactive(session, older_than=timedelta(days=90), now=NOW) == 0


def test_the_lock_can_only_be_taken_once_until_released(session):
    conversation = _thread(session, "t", idle=timedelta(0))

    assert conversation_store.try_start_query(session, conversation.id, now=NOW) is True
    assert conversation_store.try_start_query(session, conversation.id, now=NOW + timedelta(minutes=1)) is False
    assert conversation_store.try_start_query(session, conversation.id, now=NOW + timedelta(minutes=6)) is True

    conversation_store.finish_query(session, conversation.id)
    assert conversation_store.try_start_query(session, conversation.id, now=NOW + timedelta(minutes=7)) is True


def test_messages_for_a_deleted_conversation_are_not_saved(session):
    conversation = _thread(session, "gone", idle=timedelta(0))
    conversation_store.delete_conversation(session, conversation)

    assert conversation_store.append_message(session, conversation, role="assistant", content="Late answer") is False
    assert session.query(ConversationMessage).count() == 0


def test_maintenance_runs_every_task_even_if_one_fails():
    calls = []

    def broken() -> int:
        raise RuntimeError("database unavailable")

    def purge() -> int:
        calls.append("purge")
        return 3

    results = MaintenanceWorker({"broken": broken, "purge": purge}).run_once()

    assert results == {"purge": 3}
    assert calls == ["purge"]
