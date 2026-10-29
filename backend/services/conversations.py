"""Chat threads across devices: ownership, the one-question-at-a-time lock, and retention."""

from datetime import datetime, timedelta, timezone

from sqlalchemy import delete, inspect, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from backend.models import Conversation, ConversationMessage

TITLE_LENGTH = 60
# A lock older than this belongs to a request that died without releasing it.
QUERY_LOCK_STALE_AFTER = timedelta(minutes=5)
# Bulk statements skip re-checking loaded objects in Python, which fails on SQLite's naive datetimes.
_BULK = {"synchronize_session": False}


class ConversationNotFound(Exception):
    """The thread doesn't exist, or belongs to someone else (reported the same way on purpose)."""


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def as_utc(value: datetime | None) -> datetime | None:
    # SQLite hands back naive datetimes; every timestamp here is stored in UTC.
    if value is None or value.tzinfo is not None:
        return value
    return value.replace(tzinfo=timezone.utc)


def title_from(question: str | None) -> str | None:
    if not question:
        return None
    text = " ".join(question.split())
    if len(text) <= TITLE_LENGTH:
        return text
    return text[:TITLE_LENGTH].rsplit(" ", 1)[0].rstrip(" ,.;:") + "…"


def find(session: Session, *, household_id: int, session_id: str) -> Conversation | None:
    return session.scalar(
        select(Conversation).where(Conversation.household_id == household_id, Conversation.session_id == session_id)
    )


def get_owned(session: Session, *, household_id: int, session_id: str, user_id: int) -> Conversation | None:
    """The user's thread, None if it doesn't exist yet. Raises if it belongs to someone else."""
    conversation = find(session, household_id=household_id, session_id=session_id)
    if conversation is not None and conversation.user_id != user_id:
        raise ConversationNotFound(session_id)
    return conversation


def ensure(session: Session, *, household_id: int, session_id: str, user_id: int) -> Conversation:
    """The user's thread, created on its first question. Its title is set from the sanitized question later."""
    conversation = get_owned(session, household_id=household_id, session_id=session_id, user_id=user_id)
    if conversation is not None:
        return conversation
    now = utc_now()
    conversation = Conversation(
        household_id=household_id,
        user_id=user_id,
        session_id=session_id,
        created_at=now,
        updated_at=now,
    )
    session.add(conversation)
    session.commit()
    return conversation


def try_start_query(session: Session, conversation_id: int, *, now: datetime | None = None) -> bool:
    """Take the conversation's lock. False while another question in it is still being answered."""
    now = now or utc_now()
    result = session.execute(
        update(Conversation)
        .where(
            Conversation.id == conversation_id,
            (Conversation.active_query_started_at.is_(None))
            | (Conversation.active_query_started_at < now - QUERY_LOCK_STALE_AFTER),
        )
        .values(active_query_started_at=now),
        execution_options=_BULK,
    )
    session.commit()
    return result.rowcount == 1


def finish_query(session: Session, conversation_id: int) -> None:
    session.execute(
        update(Conversation).where(Conversation.id == conversation_id).values(active_query_started_at=None),
        execution_options=_BULK,
    )
    session.commit()


def append_message(
    session: Session, conversation: Conversation, *, role: str, content: str, answer: dict | None = None
) -> bool:
    """Save a message. False (and nothing saved) if the thread was deleted meanwhile, e.g. on another device."""
    return append_message_id(session, conversation, role=role, content=content, answer=answer) is not None


def append_message_id(
    session: Session, conversation: Conversation, *, role: str, content: str, answer: dict | None = None
) -> int | None:
    """Return the committed message ID, or None if its conversation was deleted.

    `answer` is the query result an assistant message came from. Its citations and urgency are
    saved with it, so the thread shows them again when it's reopened, on any device.
    """
    # Read the id from the identity key: touching an expired attribute of a deleted row would raise.
    current = session.get(Conversation, _identity(conversation), populate_existing=True)
    if current is None:
        session.rollback()
        return None
    now = utc_now()
    current.updated_at = now
    if role == "user" and current.title is None:
        current.title = title_from(content)
    message = ConversationMessage(
        conversation_id=current.id,
        household_id=current.household_id,
        session_id=current.session_id,
        role=role,
        content=content,
        created_at=now,
    )
    if answer is not None:
        message.sources = list(answer.get("sources") or [])
        message.urgency_level = answer.get("urgency_level")
        message.should_escalate = bool(answer.get("should_escalate"))
    session.add(message)
    try:
        session.flush()
        message_id = message.id
        session.commit()
    except IntegrityError:
        # Deleted between the check above and this insert.
        session.rollback()
        return None
    return message_id


def list_for_user(
    session: Session,
    *,
    household_id: int,
    user_id: int,
    updated_since: datetime | None = None,
    limit: int = 50,
) -> list[Conversation]:
    query = select(Conversation).where(Conversation.household_id == household_id, Conversation.user_id == user_id)
    if updated_since is not None:
        query = query.where(Conversation.updated_at > updated_since)
    return list(session.scalars(query.order_by(Conversation.updated_at.desc(), Conversation.id.desc()).limit(limit)))


def delete_conversation(session: Session, conversation: Conversation) -> None:
    conversation_id = _identity(conversation)
    # Messages are deleted explicitly as well, because SQLite doesn't enforce ON DELETE CASCADE by default.
    session.execute(
        delete(ConversationMessage).where(ConversationMessage.conversation_id == conversation_id),
        execution_options=_BULK,
    )
    session.execute(delete(Conversation).where(Conversation.id == conversation_id), execution_options=_BULK)
    session.commit()


def purge_inactive(session: Session, *, older_than: timedelta, now: datetime | None = None) -> int:
    """Delete threads with no activity for `older_than`, with their messages. Returns how many."""
    cutoff = (now or utc_now()) - older_than
    stale_ids = list(
        session.scalars(
            select(Conversation.id).where(
                Conversation.updated_at < cutoff,
                Conversation.active_query_started_at.is_(None),
            )
        )
    )
    if not stale_ids:
        return 0
    session.execute(
        delete(ConversationMessage).where(ConversationMessage.conversation_id.in_(stale_ids)),
        execution_options=_BULK,
    )
    session.execute(delete(Conversation).where(Conversation.id.in_(stale_ids)), execution_options=_BULK)
    session.commit()
    return len(stale_ids)


def _identity(conversation: Conversation) -> int:
    identity = inspect(conversation).identity
    return identity[0] if identity else conversation.id
