"""Regenerate the API contract the native apps test against.

    python scripts/export_contract.py                   # refuses breaking changes
    python scripts/export_contract.py --allow-breaking  # after updating every app

Writes contracts/openapi.json and contracts/fixtures/ (recorded responses and SSE streams).
Commit them together with the API change; the Android tests read the fixtures.
"""

import argparse
import json
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
for path in (ROOT, ROOT / "tests", ROOT / "tests" / "contract"):
    sys.path.insert(0, str(path))

import hb_test_env  # noqa: E402,F401  (test settings: SQLite, no real AI calls)
from contract_scenarios import FIXTURES_DIR, OPENAPI_PATH, breaking_changes, generate, openapi_schema  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--allow-breaking", action="store_true", help="write the contract even if it breaks older apps")
    args = parser.parse_args()

    schema = openapi_schema()
    if OPENAPI_PATH.exists():
        problems = breaking_changes(json.loads(OPENAPI_PATH.read_text()), json.loads(schema))
        if problems and not args.allow_breaking:
            print("These changes would break apps built against the current contract:", file=sys.stderr)
            for problem in problems:
                print(f"  - {problem}", file=sys.stderr)
            print("Make the change additive, or rerun with --allow-breaking once every app handles it.", file=sys.stderr)
            return 1

    with tempfile.TemporaryDirectory() as tmp:
        fixtures = generate(Path(tmp))

    FIXTURES_DIR.mkdir(parents=True, exist_ok=True)
    OPENAPI_PATH.write_text(schema)
    for stale in set(p.name for p in FIXTURES_DIR.iterdir()) - set(fixtures):
        (FIXTURES_DIR / stale).unlink()
    for name, content in fixtures.items():
        (FIXTURES_DIR / name).write_text(content)
    print(f"Wrote {OPENAPI_PATH.relative_to(ROOT)} and {len(fixtures)} fixtures in {FIXTURES_DIR.relative_to(ROOT)}.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
