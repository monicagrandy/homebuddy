"""Live smoke test of a deployed backend, as the Android app uses it.

    python scripts/mobile_smoke.py --base-url https://api.homebuddy.chat --token "$HOMEBUDDY_TEST_TOKEN"

Use a dedicated test account in the beta group, with a household and at least one document
(see docs/android-testing.md for getting its token). The run asks two questions in two new
conversations and deletes them at the end. It checks structure, not answer quality; that is
what tests/run_evals.py is for.
"""

import argparse
import json
import sys
import uuid

import httpx

DOCUMENT_QUESTION = "What does my manual say about cleaning or maintenance?"
SAFETY_QUESTION = "I smell gas in the kitchen"


def parse_sse(text: str) -> list[dict]:
    """The events of a /query/stream body, skipping ping comments."""
    events, data = [], []
    for line in text.splitlines() + [""]:
        if line.startswith(":"):
            continue
        if line.startswith("data:"):
            data.append(line[5:].lstrip())
        elif not line and data:
            events.append(json.loads("\n".join(data)))
            data = []
    return events


class Smoke:
    def __init__(self, base_url: str, token: str) -> None:
        self.client = httpx.Client(
            base_url=base_url.rstrip("/"),
            headers={"Authorization": f"Bearer {token}", "X-Client": "smoke/1.0"},
            timeout=httpx.Timeout(10.0, read=120.0),
        )
        self.failures: list[str] = []
        self.conversations: list[tuple[int, str]] = []

    def check(self, name: str, ok: bool, detail: str = "") -> bool:
        print(f"{'PASS' if ok else 'FAIL'}  {name}{f' ({detail})' if detail else ''}")
        if not ok:
            self.failures.append(name)
        return ok

    def ask(self, household_id: int, zip_code: str, question: str) -> dict | None:
        session_id = f"smoke-{uuid.uuid4()}"
        self.conversations.append((household_id, session_id))
        response = self.client.post(
            "/query/stream",
            json={"question": question, "session_id": session_id, "household_id": household_id, "household_zip_code": zip_code},
        )
        if not self.check(f"stream answers {question!r}", response.status_code == 200, f"HTTP {response.status_code}"):
            return None
        events = parse_sse(response.text)
        self.check("every event is versioned", all(event.get("v") == 1 for event in events))
        final = events[-1] if events else {}
        if not self.check("the stream ends with a final answer", final.get("type") == "final", f"last event: {final.get('type')}"):
            return None
        return final["result"]

    def run(self, check_docs_hidden: bool) -> int:
        config = self.client.get("/client-config")
        self.check("client config is public", config.status_code == 200 and "android" in config.json().get("min_supported", {}))
        if check_docs_hidden:
            self.check("API docs are hidden", self.client.get("/docs").status_code == 404)

        me = self.client.get("/auth/me")
        if not self.check("the test account is signed in", me.status_code == 200, f"HTTP {me.status_code}"):
            return 1
        households = self.client.get("/households").json()
        if not self.check("the test account has a household", bool(households)):
            return 1
        home = households[0]
        documents = self.client.get("/documents", params={"household_id": home["id"]}).json()
        self.check("documents list", isinstance(documents, list), f"{len(documents)} documents")

        try:
            answer = self.ask(home["id"], home["zip_code"], DOCUMENT_QUESTION)
            if answer is not None and documents:
                self.check("a document question cites sources", bool(answer.get("sources")), f"{len(answer.get('sources', []))} sources")
            safety = self.ask(home["id"], home["zip_code"], SAFETY_QUESTION)
            if safety is not None:
                self.check("the gas question escalates", safety.get("should_escalate") is True, f"urgency {safety.get('urgency_level')}")

            listed = {c["id"] for c in self.client.get("/conversations", params={"household_id": home["id"]}).json()}
            self.check("both conversations are listed", all(session_id in listed for _, session_id in self.conversations))
        finally:
            for household_id, session_id in self.conversations:
                self.client.delete(f"/conversations/{session_id}", params={"household_id": household_id})

        print(f"\n{len(self.failures)} failed" if self.failures else "\nAll checks passed.")
        return 1 if self.failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="https://api.homebuddy.chat")
    parser.add_argument("--token", required=True, help="access token of the test account")
    parser.add_argument("--allow-docs", action="store_true", help="skip the hidden-docs check (local runs)")
    args = parser.parse_args()
    return Smoke(args.base_url, args.token).run(check_docs_hidden=not args.allow_docs)


if __name__ == "__main__":
    sys.exit(main())
