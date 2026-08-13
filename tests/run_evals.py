"""Entrypoint for the Home Buddy evaluation suites.

Run from the repo root:

    python3 tests/run_evals.py
    python3 tests/run_evals.py routing
    python3 tests/run_evals.py grounding
    python3 tests/run_evals.py workflow
    python3 tests/run_evals.py safety
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

os.environ.setdefault("CONTRACTOR_SEARCH_PROVIDER", "mock")

REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from tests.evals.home_buddy_eval import run_all_suites, run_suite


def main() -> None:
    suite_name = sys.argv[1] if len(sys.argv) > 1 else None

    if suite_name:
        report = run_suite(suite_name)
    else:
        report = run_all_suites()

    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
