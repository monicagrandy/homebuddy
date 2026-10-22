import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def _smoke():
    spec = importlib.util.spec_from_file_location("mobile_smoke", ROOT / "scripts" / "mobile_smoke.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_smoke_script_reads_the_recorded_streams():
    parse_sse = _smoke().parse_sse

    answer = parse_sse((ROOT / "contracts/fixtures/query_stream_answer.sse").read_text())
    safety = parse_sse((ROOT / "contracts/fixtures/query_stream_safety.sse").read_text())

    assert [event["type"] for event in answer] == ["user_accepted", "status", "status", "status", "final"]
    assert answer[-1]["result"]["sources"][0]["display_name"] == "Bosch Dishwasher Manual"
    assert safety[-1]["result"]["should_escalate"] is True


def test_smoke_script_skips_pings():
    events = _smoke().parse_sse(': ping\n\ndata: {"v": 1, "type": "token", "text": "a"}\n\n: ping\n\n')

    assert events == [{"v": 1, "type": "token", "text": "a"}]
