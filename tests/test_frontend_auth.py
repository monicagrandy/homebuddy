import base64
import json

from frontend_auth import (
    access_token_needs_renewal,
    claim_auth_code,
    jwt_expiration,
    jwt_is_expired,
    store_renewed_tokens,
)


def _token(payload: dict) -> str:
    encoded = base64.urlsafe_b64encode(json.dumps(payload).encode()).decode().rstrip("=")
    return f"header.{encoded}.signature"


def test_jwt_expiration_reads_exp_claim():
    assert jwt_expiration(_token({"exp": 1234})) == 1234


def test_jwt_is_expired_uses_supplied_clock():
    token = _token({"exp": 1234})

    assert jwt_is_expired(token, now=1234)
    assert not jwt_is_expired(token, now=1233)


def test_malformed_jwt_is_treated_as_expired():
    assert jwt_expiration("not-a-jwt") is None
    assert jwt_is_expired("not-a-jwt")


def test_auth_code_can_only_be_claimed_once():
    state = {}

    assert claim_auth_code(state, "one-time-code")
    assert state["processed_auth_code"] == "one-time-code"
    assert not claim_auth_code(state, "one-time-code")
    assert claim_auth_code(state, "new-code")


def test_jwt_is_expired_reports_tokens_inside_the_leeway():
    token = _token({"exp": 1000})

    assert jwt_is_expired(token, now=881, leeway=120)
    assert not jwt_is_expired(token, now=879, leeway=120)


def test_access_token_needs_renewal_only_with_a_refresh_token():
    expiring = _token({"exp": 1000})

    assert access_token_needs_renewal(
        {"auth_token": expiring, "refresh_token": "refresh"}, leeway=120, now=950
    )
    assert not access_token_needs_renewal(
        {"auth_token": expiring, "refresh_token": "refresh"}, leeway=120, now=800
    )
    assert not access_token_needs_renewal(
        {"auth_token": expiring, "refresh_token": None}, leeway=120, now=950
    )
    assert not access_token_needs_renewal(
        {"auth_token": None, "refresh_token": "refresh"}, leeway=120, now=950
    )


def test_store_renewed_tokens_keeps_refresh_token_without_rotation():
    state = {"refresh_token": "original-refresh", "auth_storage_action": "read"}

    store_renewed_tokens(state, {"access_token": "new-access", "id_token": "new-id"})

    assert state["auth_token"] == "new-access"
    assert state["access_token"] == "new-access"
    assert state["id_token"] == "new-id"
    assert state["refresh_token"] == "original-refresh"
    assert state["auth_storage_action"] == "sync"


def test_store_renewed_tokens_takes_a_rotated_refresh_token():
    state = {"refresh_token": "original-refresh", "auth_storage_action": "sync"}

    store_renewed_tokens(
        state,
        {"access_token": "new-access", "id_token": "new-id", "refresh_token": "rotated-refresh"},
    )

    assert state["refresh_token"] == "rotated-refresh"
    assert state["auth_storage_action"] == "sync"


def test_store_renewed_tokens_leaves_pending_storage_actions_alone():
    for pending in ("write", "clear"):
        state = {"refresh_token": "refresh", "auth_storage_action": pending}

        store_renewed_tokens(state, {"access_token": "new-access", "id_token": "new-id"})

        assert state["auth_storage_action"] == pending
