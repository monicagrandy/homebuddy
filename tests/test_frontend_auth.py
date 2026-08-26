import base64
import json

from frontend_auth import claim_auth_code, jwt_expiration, jwt_is_expired


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
