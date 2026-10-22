"""The committed contract (contracts/) must match the API. Regenerate with scripts/export_contract.py."""

import json

from contract_scenarios import FIXTURES_DIR, OPENAPI_PATH, breaking_changes, generate, openapi_schema

REGENERATE = "API contract is out of date: run `python scripts/export_contract.py` and commit contracts/."


def test_openapi_snapshot_matches_the_api():
    assert openapi_schema() == OPENAPI_PATH.read_text(), REGENERATE


def test_recorded_fixtures_match_the_api(tmp_path):
    committed = {path.name: path.read_text() for path in FIXTURES_DIR.iterdir()}

    generated = generate(tmp_path)

    assert sorted(generated) == sorted(committed), REGENERATE
    for name, content in generated.items():
        assert content == committed[name], f"{name}: {REGENERATE}"


def _sse_events(name: str) -> list[dict]:
    lines = (FIXTURES_DIR / name).read_text().splitlines()
    return [json.loads(line[len("data: "):]) for line in lines if line.startswith("data: ")]


def test_every_streamed_event_is_versioned_and_ends_with_final_or_error():
    for name in ("query_stream_answer.sse", "query_stream_safety.sse", "query_stream_error.sse"):
        events = _sse_events(name)
        assert all(event["v"] == 1 for event in events), name
        assert events[0]["type"] == "user_accepted" and events[0]["conversation_id"], name
        assert events[-1]["type"] in {"final", "error"}, name


def test_the_safety_stream_carries_the_escalation_flags_the_apps_key_on():
    final = _sse_events("query_stream_safety.sse")[-1]["result"]

    assert final["should_escalate"] is True
    assert final["urgency_level"] == "critical"


def _schema(paths=None, schemas=None) -> dict:
    return {"paths": paths or {}, "components": {"schemas": schemas or {}}}


def test_breaking_changes_are_detected():
    old = _schema(
        paths={"/documents": {"get": {}, "delete": {}}},
        schemas={"DocumentOut": {"properties": {"id": {"type": "integer"}, "status": {"type": "string"}}}},
    )
    new = _schema(
        paths={"/documents": {"get": {}}},
        schemas={"DocumentOut": {"properties": {"id": {"type": "string"}}}},
    )

    assert breaking_changes(old, new) == [
        "removed DELETE /documents",
        "changed type of DocumentOut.id: integer -> string",
        "removed field DocumentOut.status",
    ]


def test_additive_changes_are_not_breaking():
    old = _schema(paths={"/a": {"get": {}}}, schemas={"Out": {"properties": {"id": {"type": "integer"}}}})
    new = _schema(
        paths={"/a": {"get": {}, "post": {}}, "/b": {"get": {}}},
        schemas={
            "Out": {"properties": {"id": {"type": "integer"}, "extra": {"type": "string"}}},
            "New": {"properties": {}},
        },
    )

    assert breaking_changes(old, new) == []
