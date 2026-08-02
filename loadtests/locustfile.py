from __future__ import annotations

import json
import os
import random
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from locust import HttpUser, between, events, task

ROOT = Path(__file__).resolve().parent
DEFAULT_USERS_FILE = ROOT / "users.json"
DEFAULT_PROMPTS_FILE = ROOT / "prompts.json"
DEFAULT_MAX_FAILURE_RATIO = 0.01
DEFAULT_P95_MS = 15_000.0
DEFAULT_TIMEOUT_SECONDS = 180.0


@dataclass(frozen=True)
class LoadTestUser:
    label: str
    access_token: str
    household_id: int
    household_zip_code: str | None = None
    entry_id: str | None = None
    asset_id: int | None = None


@dataclass(frozen=True)
class QueryPrompt:
    label: str
    question: str
    weight: int = 1


def _read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def _load_users() -> list[LoadTestUser]:
    path = Path(os.getenv("HOMEBUDDY_LOADTEST_USERS_FILE", str(DEFAULT_USERS_FILE))).expanduser()
    if not path.exists():
        raise RuntimeError(
            f"Missing load-test users file at {path}. Copy loadtests/users.example.json to "
            f"loadtests/users.json and add real bearer tokens before running Locust."
        )

    raw_users = _read_json(path)
    if not isinstance(raw_users, list) or not raw_users:
        raise RuntimeError("Load-test users file must contain a non-empty JSON array.")

    users: list[LoadTestUser] = []
    for index, raw in enumerate(raw_users, start=1):
        if not isinstance(raw, dict):
            raise RuntimeError(f"Load-test user #{index} must be an object.")

        label = str(raw.get("label") or f"user-{index}").strip()
        access_token = str(raw.get("access_token") or "").strip()
        household_id = raw.get("household_id")

        if not access_token:
            raise RuntimeError(f"Load-test user '{label}' is missing access_token.")
        if not isinstance(household_id, int):
            raise RuntimeError(f"Load-test user '{label}' is missing integer household_id.")

        users.append(
            LoadTestUser(
                label=label,
                access_token=access_token,
                household_id=household_id,
                household_zip_code=str(raw.get("household_zip_code")).strip()
                if raw.get("household_zip_code") is not None
                else None,
                entry_id=str(raw.get("entry_id")).strip() if raw.get("entry_id") is not None else None,
                asset_id=raw.get("asset_id") if isinstance(raw.get("asset_id"), int) else None,
            )
        )

    return users


def _default_prompts() -> list[QueryPrompt]:
    return [
        QueryPrompt(
            label="capabilities",
            question="What can you help me with as a homeowner?",
            weight=3,
        ),
        QueryPrompt(
            label="task_draft",
            question="Create a reminder to replace my HVAC filter next month.",
            weight=2,
        ),
        QueryPrompt(
            label="case_draft",
            question="Draft a case for a dishwasher that keeps leaking under the door.",
            weight=1,
        ),
    ]


def _load_prompts() -> list[QueryPrompt]:
    path = Path(os.getenv("HOMEBUDDY_LOADTEST_PROMPTS_FILE", str(DEFAULT_PROMPTS_FILE))).expanduser()
    if not path.exists():
        return _default_prompts()

    raw_prompts = _read_json(path)
    if not isinstance(raw_prompts, list) or not raw_prompts:
        raise RuntimeError("Load-test prompts file must contain a non-empty JSON array.")

    prompts: list[QueryPrompt] = []
    for index, raw in enumerate(raw_prompts, start=1):
        if not isinstance(raw, dict):
            raise RuntimeError(f"Load-test prompt #{index} must be an object.")
        question = str(raw.get("question") or "").strip()
        if not question:
            raise RuntimeError(f"Load-test prompt #{index} is missing question.")
        weight = raw.get("weight", 1)
        prompts.append(
            QueryPrompt(
                label=str(raw.get("label") or f"prompt-{index}").strip(),
                question=question,
                weight=int(weight) if isinstance(weight, int) and weight > 0 else 1,
            )
        )
    return prompts


USERS = _load_users()
PROMPTS = _load_prompts()
PROMPT_POPULATION = [prompt for prompt in PROMPTS for _ in range(prompt.weight)]
REQUEST_TIMEOUT_SECONDS = float(
    os.getenv("HOMEBUDDY_LOADTEST_TIMEOUT_SECONDS", str(DEFAULT_TIMEOUT_SECONDS))
)


def _choose_user() -> LoadTestUser:
    return random.choice(USERS)


def _choose_prompt() -> QueryPrompt:
    return random.choice(PROMPT_POPULATION)


class HomeBuddyApiUser(HttpUser):
    host = os.getenv("HOMEBUDDY_LOADTEST_HOST", "http://localhost:8000")
    wait_time = between(1, 4)

    def on_start(self) -> None:
        self.profile = _choose_user()
        self.session_id = f"locust-{self.profile.label}-{uuid.uuid4().hex[:12]}"
        self.headers = {"Authorization": f"Bearer {self.profile.access_token}"}

        with self.client.get(
            "/auth/me",
            headers=self.headers,
            name="/auth/me",
            catch_response=True,
            timeout=REQUEST_TIMEOUT_SECONDS,
        ) as response:
            if response.status_code != 200:
                response.failure(
                    f"Auth bootstrap failed for {self.profile.label}: {response.status_code} {response.text}"
                )
            else:
                response.success()

        self.client.delete(
            f"/conversations/{self.session_id}/messages",
            headers=self.headers,
            params={"household_id": self.profile.household_id},
            name="/conversations/[session]/messages [DELETE]",
            timeout=REQUEST_TIMEOUT_SECONDS,
        )

    @task(2)
    def list_households(self) -> None:
        with self.client.get(
            "/households",
            headers=self.headers,
            name="/households",
            catch_response=True,
            timeout=REQUEST_TIMEOUT_SECONDS,
        ) as response:
            if response.status_code != 200:
                response.failure(f"Unexpected /households status {response.status_code}: {response.text}")
                return

            try:
                payload = response.json()
            except ValueError as exc:
                response.failure(f"/households returned invalid JSON: {exc}")
                return

            if not isinstance(payload, list):
                response.failure("/households did not return a list.")
                return

            response.success()

    @task(1)
    def list_conversation_messages(self) -> None:
        with self.client.get(
            f"/conversations/{self.session_id}/messages",
            headers=self.headers,
            params={"household_id": self.profile.household_id},
            name="/conversations/[session]/messages [GET]",
            catch_response=True,
            timeout=REQUEST_TIMEOUT_SECONDS,
        ) as response:
            if response.status_code != 200:
                response.failure(
                    f"Unexpected conversation history status {response.status_code}: {response.text}"
                )
                return

            try:
                payload = response.json()
            except ValueError as exc:
                response.failure(f"Conversation history returned invalid JSON: {exc}")
                return

            if not isinstance(payload, list):
                response.failure("Conversation history did not return a list.")
                return

            response.success()

    @task(3)
    def run_query(self) -> None:
        prompt = _choose_prompt()
        payload = {
            "question": prompt.question,
            "session_id": self.session_id,
            "response_mode": "web",
            "household_id": self.profile.household_id,
            "household_zip_code": self.profile.household_zip_code,
            "entry_id": self.profile.entry_id,
            "asset_id": self.profile.asset_id,
        }

        with self.client.post(
            "/query",
            json=payload,
            headers=self.headers,
            name=f"/query [{prompt.label}]",
            catch_response=True,
            timeout=REQUEST_TIMEOUT_SECONDS,
        ) as response:
            if response.status_code != 200:
                response.failure(f"Unexpected /query status {response.status_code}: {response.text}")
                return

            try:
                body = response.json()
            except ValueError as exc:
                response.failure(f"/query returned invalid JSON: {exc}")
                return

            answer = str(body.get("answer") or "").strip()
            if not answer:
                response.failure("/query response did not include an answer.")
                return

            response.success()


@events.quitting.add_listener
def set_exit_code(environment, **_kwargs) -> None:
    total = environment.stats.total
    if total.num_requests == 0:
        environment.process_exit_code = 1
        return

    max_failure_ratio = float(
        os.getenv("HOMEBUDDY_MAX_FAILURE_RATIO", str(DEFAULT_MAX_FAILURE_RATIO))
    )
    max_p95_ms = float(os.getenv("HOMEBUDDY_P95_MS", str(DEFAULT_P95_MS)))
    p95_ms = total.get_response_time_percentile(0.95) or 0.0

    if total.fail_ratio > max_failure_ratio:
        environment.process_exit_code = 1
    elif p95_ms > max_p95_ms:
        environment.process_exit_code = 1
    else:
        environment.process_exit_code = 0
