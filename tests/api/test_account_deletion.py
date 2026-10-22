"""DELETE /me (required by Google Play) and GET /client-config."""

from datetime import datetime, timezone

import pytest
from fastapi.testclient import TestClient

from backend import api
from backend.auth import AuthenticatedIdentity
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
from backend.services.accounts import CognitoUserDeleter
from cognito_fakes import TEST_MOBILE_CLIENT_ID, TEST_WEB_CLIENT_ID

ALL_TABLES = (
    User, Household, HouseholdMember, HouseholdInvitation, Asset, Document, DocumentChunk,
    Case, MaintenanceTask, Conversation, ConversationMessage, IngestionJob, ContractorSearchUsage,
)


class StubCognito:
    def __init__(self, error: Exception | None = None) -> None:
        self.deleted: list[str] = []
        self.error = error

    def delete(self, username: str) -> None:
        if self.error is not None:
            raise self.error
        self.deleted.append(username)


@pytest.fixture
def cognito(client) -> StubCognito:
    stub = StubCognito()
    api.app.dependency_overrides[api.get_cognito_user_deleter] = lambda: stub
    return stub


def _seed_household(session, user_id: int, *, name="Home") -> int:
    household = Household(name=name, zip_code="80202", home_type="condo")
    session.add(household)
    session.flush()
    hid = household.id
    asset = Asset(household_id=hid, name="Dishwasher")
    session.add_all([HouseholdMember(household_id=hid, user_id=user_id, role="owner"), asset])
    session.flush()
    session.add_all(
        [
            Document(entry_id="manual", household_id=hid, asset_id=asset.id, doc_type="manual",
                     source_name="Manual", uploaded_by_user_id=user_id),
            DocumentChunk(household_id=hid, entry_id="manual", doc_type="manual", chunk_index=0, source="manual", text="Clean it."),
            Case(household_id=hid, title="Leak", summary="Under sink", severity="low", status="open", contractor_trade="plumber"),
            MaintenanceTask(household_id=hid, title="Change filter", status="open"),
            HouseholdInvitation(household_id=hid, email="friend@example.com", role="member", status="pending",
                                token=f"token-{hid}", invited_by_user_id=user_id),
            IngestionJob(id=f"job-{hid}", household_id=hid, user_id=user_id, entry_id="manual",
                         created_document=True, status="succeeded", attempts=1, run_after=datetime.now(timezone.utc)),
        ]
    )
    conversation = Conversation(household_id=hid, user_id=user_id, session_id=f"thread-{hid}", title="Hi")
    session.add(conversation)
    session.flush()
    session.add(ConversationMessage(conversation_id=conversation.id, household_id=hid, session_id=conversation.session_id,
                                    role="user", content="Hi"))
    session.commit()
    return hid


def _counts(client) -> dict[str, int]:
    with client.testing_session_local() as session:
        return {model.__tablename__: session.query(model).count() for model in ALL_TABLES}


def _me(client) -> User:
    client.get("/auth/me")
    with client.testing_session_local() as session:
        return session.query(User).filter_by(cognito_sub="dev-user-sub").one()


def test_deleting_the_only_member_removes_every_trace(client, cognito):
    user = _me(client)
    with client.testing_session_local() as session:
        _seed_household(session, user.id)
        _seed_household(session, user.id, name="Cabin")
        session.add(ContractorSearchUsage(user_id=user.id, period_start=datetime.now(timezone.utc), searches_used=1))
        session.commit()

    response = client.delete("/me")

    assert response.status_code == 204
    assert all(count == 0 for count in _counts(client).values()), _counts(client)
    assert cognito.deleted == ["dev-user-sub"]


def test_in_a_shared_household_only_my_personal_data_goes(client, cognito):
    user = _me(client)
    with client.testing_session_local() as session:
        household_id = _seed_household(session, user.id)
        other = User(cognito_sub="other-sub", email="other@example.com")
        session.add(other)
        session.flush()
        session.add(HouseholdMember(household_id=household_id, user_id=other.id, role="member"))
        other_thread = Conversation(household_id=household_id, user_id=other.id, session_id="theirs")
        session.add(other_thread)
        session.commit()
        other_id = other.id

    assert client.delete("/me").status_code == 204

    with client.testing_session_local() as session:
        assert [u.cognito_sub for u in session.query(User)] == ["other-sub"]
        assert session.query(Household).count() == 1
        assert [m.user_id for m in session.query(HouseholdMember)] == [other_id]
        assert [c.session_id for c in session.query(Conversation)] == ["theirs"]
        document = session.query(Document).one()
        assert document.uploaded_by_user_id is None
        assert session.query(DocumentChunk).count() == 1
        assert session.query(IngestionJob).count() == 0


def test_deleting_again_retries_the_cognito_step(client, cognito):
    _me(client)
    assert client.delete("/me").status_code == 204

    assert client.delete("/me").status_code == 204
    assert cognito.deleted == ["dev-user-sub", "dev-user-sub"]


def test_a_failed_cognito_step_reports_502_and_can_be_retried(client):
    failing = StubCognito(error=RuntimeError("AWS unavailable"))
    api.app.dependency_overrides[api.get_cognito_user_deleter] = lambda: failing
    _me(client)

    response = client.delete("/me")

    assert response.status_code == 502
    assert "data was deleted" in response.json()["detail"]
    assert _counts(client)["users"] == 0

    working = StubCognito()
    api.app.dependency_overrides[api.get_cognito_user_deleter] = lambda: working
    assert client.delete("/me").status_code == 204
    assert working.deleted == ["dev-user-sub"]


def test_without_cognito_configured_the_data_is_still_deleted(client):
    api.app.dependency_overrides[api.get_cognito_user_deleter] = lambda: None
    _me(client)

    assert client.delete("/me").status_code == 204
    assert _counts(client)["users"] == 0


def test_cognito_username_is_used_when_it_differs_from_sub(client, cognito):
    api.app.dependency_overrides[api.get_current_identity] = lambda: AuthenticatedIdentity(
        subject="dev-user-sub", email="dev@example.com", username="pat"
    )
    _me(client)

    client.delete("/me")

    assert cognito.deleted == ["pat"]


def test_another_device_is_signed_out_once_the_account_is_deleted(client, cognito_tokens, cognito):
    api.app.dependency_overrides.pop(api.get_current_identity)
    phone = cognito_tokens.access_token(sub="shared-sub", client_id=TEST_MOBILE_CLIENT_ID)
    laptop = cognito_tokens.access_token(sub="shared-sub", client_id=TEST_WEB_CLIENT_ID)
    client.post(
        "/auth/bootstrap",
        json={"id_token": cognito_tokens.id_token(sub="shared-sub", email="s@example.com", access_token=laptop),
              "access_token": laptop},
    )
    assert client.get("/households", headers={"Authorization": f"Bearer {laptop}"}).status_code == 200

    assert client.delete("/me", headers={"Authorization": f"Bearer {phone}"}).status_code == 204

    assert client.get("/households", headers={"Authorization": f"Bearer {laptop}"}).status_code == 401
    assert client.get("/auth/me", headers={"Authorization": f"Bearer {laptop}"}).status_code == 401


class FakeCognitoClient:
    class exceptions:
        class UserNotFoundException(Exception):
            pass

    def __init__(self, missing: bool = False) -> None:
        self.missing = missing
        self.calls: list[dict] = []

    def admin_delete_user(self, **kwargs) -> None:
        self.calls.append(kwargs)
        if self.missing:
            raise self.exceptions.UserNotFoundException()


def test_cognito_deleter_calls_admin_delete_user():
    fake = FakeCognitoClient()

    CognitoUserDeleter(user_pool_id="us-east-2_pool", region="us-east-2", client=fake).delete("pat")

    assert fake.calls == [{"UserPoolId": "us-east-2_pool", "Username": "pat"}]


def test_cognito_deleter_treats_an_already_deleted_user_as_done():
    CognitoUserDeleter(user_pool_id="p", region="us-east-2", client=FakeCognitoClient(missing=True)).delete("pat")


def test_client_config_is_public(client: TestClient, monkeypatch):
    api.app.dependency_overrides.pop(api.get_current_identity)
    monkeypatch.setattr(api.settings, "min_android_version", "1.2.0")

    response = client.get("/client-config")

    assert response.status_code == 200
    assert response.json() == {
        "min_supported": {"android": "1.2.0"},
        "retention_days": 90,
        "features": {"home_operations": api.settings.home_operations_enabled},
    }
