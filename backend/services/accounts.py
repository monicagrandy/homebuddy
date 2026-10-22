"""Deleting an account: the user's data in Postgres, then their Cognito sign-in."""

from sqlalchemy import delete, func, select, update
from sqlalchemy.orm import Session

from backend.config import get_logger
from backend.models import (
    Asset,
    Case,
    ContractorSearchUsage,
    Conversation,
    ConversationMessage,
    Document,
    DocumentChunk,
    Household,
    HouseholdInvitation,
    HouseholdMember,
    IngestionJob,
    MaintenanceTask,
    User,
)

logger = get_logger(__name__)

_BULK = {"synchronize_session": False}

# Children before parents. Postgres would cascade most of these, but deleting explicitly
# also works on SQLite (which doesn't enforce foreign keys by default) and keeps the order obvious.
_HOUSEHOLD_TABLES = (
    DocumentChunk,
    ConversationMessage,
    Conversation,
    IngestionJob,
    Document,
    Case,
    MaintenanceTask,
    Asset,
    HouseholdInvitation,
    HouseholdMember,
)


def _delete_household(session: Session, household_id: int) -> None:
    for model in _HOUSEHOLD_TABLES:
        session.execute(delete(model).where(model.household_id == household_id), execution_options=_BULK)
    session.execute(delete(Household).where(Household.id == household_id), execution_options=_BULK)


def _delete_personal_data(session: Session, *, user_id: int, household_id: int | None = None) -> None:
    conversation_ids = select(Conversation.id).where(Conversation.user_id == user_id)
    jobs = delete(IngestionJob).where(IngestionJob.user_id == user_id)
    if household_id is not None:
        conversation_ids = conversation_ids.where(Conversation.household_id == household_id)
        jobs = jobs.where(IngestionJob.household_id == household_id)
    ids = list(session.scalars(conversation_ids))
    if ids:
        session.execute(delete(ConversationMessage).where(ConversationMessage.conversation_id.in_(ids)), execution_options=_BULK)
        session.execute(delete(Conversation).where(Conversation.id.in_(ids)), execution_options=_BULK)
    session.execute(jobs, execution_options=_BULK)


def delete_account(session: Session, user_id: int) -> list[int]:
    """Delete the user and everything only they could see. Returns the ids of the households removed.

    A household the user is the only member of is deleted with all of its documents and chats.
    In a household shared with others (sharing comes later), only the user's membership,
    conversations and jobs go; the documents they added stay for the others.
    """
    removed: list[int] = []
    for membership in session.scalars(select(HouseholdMember).where(HouseholdMember.user_id == user_id)).all():
        others = session.scalar(
            select(func.count())
            .select_from(HouseholdMember)
            .where(HouseholdMember.household_id == membership.household_id, HouseholdMember.user_id != user_id)
        )
        if others:
            _delete_personal_data(session, user_id=user_id, household_id=membership.household_id)
            session.execute(
                update(Document)
                .where(Document.household_id == membership.household_id, Document.uploaded_by_user_id == user_id)
                .values(uploaded_by_user_id=None),
                execution_options=_BULK,
            )
            session.execute(delete(HouseholdMember).where(HouseholdMember.id == membership.id), execution_options=_BULK)
        else:
            _delete_household(session, membership.household_id)
            removed.append(membership.household_id)

    _delete_personal_data(session, user_id=user_id)
    session.execute(delete(ContractorSearchUsage).where(ContractorSearchUsage.user_id == user_id), execution_options=_BULK)
    session.execute(
        delete(HouseholdInvitation).where(HouseholdInvitation.invited_by_user_id == user_id), execution_options=_BULK
    )
    session.execute(delete(User).where(User.id == user_id), execution_options=_BULK)
    session.commit()
    return removed


class CognitoUserDeleter:
    """Deletes a user from the Cognito user pool (needs an IAM key allowed cognito-idp:AdminDeleteUser)."""

    def __init__(self, *, user_pool_id: str, region: str, client=None) -> None:
        self._user_pool_id = user_pool_id
        self._region = region
        self._client = client

    def _cognito(self):
        if self._client is None:
            import boto3

            self._client = boto3.client("cognito-idp", region_name=self._region)
        return self._client

    def delete(self, username: str) -> None:
        client = self._cognito()
        try:
            client.admin_delete_user(UserPoolId=self._user_pool_id, Username=username)
        except client.exceptions.UserNotFoundException:
            # Already deleted, e.g. by an earlier attempt: deleting is safe to retry.
            logger.info("Cognito user was already deleted")
