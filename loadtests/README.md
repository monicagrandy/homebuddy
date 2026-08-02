# HomeBuddy Load Testing

This directory contains an automated API load-test harness for HomeBuddy using
Locust.

Why API load testing first:

- It exercises the real critical path that froze the instance: auth, guardrails,
  routing, LLM calls, and database writes.
- It is much easier to automate repeatably than the Cognito hosted UI or
  Streamlit browser interactions.
- It can run headless and return a pass/fail exit code for automation.

## What it tests

The default Locust tasks hit:

- `GET /auth/me`
- `GET /households`
- `GET /conversations/{session_id}/messages`
- `POST /query`

The default prompts are intentionally chosen to stay on the HomeBuddy critical
path without depending on Tavily or Yelp. You can override them with your own
`loadtests/prompts.json`.

## Install locally

```bash
pip install -r requirements-loadtest.txt
```

## Configure test users

Copy the example file:

```bash
cp loadtests/users.example.json loadtests/users.json
```

Then replace the placeholder token with one or more real Cognito access tokens
for beta users that already belong to your allowed group.

Each entry needs:

- `label`
- `access_token`
- `household_id`

Optional fields:

- `household_zip_code`
- `entry_id`
- `asset_id`

## Run locally against a local backend

```bash
cp loadtests/prompts.example.json loadtests/prompts.json
locust -f loadtests/locustfile.py --headless --host http://localhost:8000 --users 3 --spawn-rate 1 --run-time 2m --stop-timeout 30s --html loadtests/reports/local-smoke.html --csv loadtests/reports/local-smoke
```

## Run inside the Lightsail Docker network

HomeBuddy does not expose the backend publicly, so the easiest way to load test
the deployed stack is to run Locust as another Docker service on the same
Compose network.

First create the real fixture files on the server:

```bash
cp loadtests/users.example.json loadtests/users.json
cp loadtests/prompts.example.json loadtests/prompts.json
```

Then build the optional Locust image:

```bash
docker compose -f docker-compose.aws.yml -f docker-compose.loadtest.yml build locust
```

Run a smoke test:

```bash
docker compose -f docker-compose.aws.yml -f docker-compose.loadtest.yml run --rm locust --headless --users 1 --spawn-rate 1 --run-time 1m --stop-timeout 30s --html /loadtests/reports/smoke.html --csv /loadtests/reports/smoke
```

Run a small beta test:

```bash
docker compose -f docker-compose.aws.yml -f docker-compose.loadtest.yml run --rm locust --headless --users 5 --spawn-rate 1 --run-time 5m --stop-timeout 30s --html /loadtests/reports/beta.html --csv /loadtests/reports/beta
```

## Suggested progression

Start with these in order:

1. `1 user` for `1 minute`
2. `3 users` for `3 minutes`
3. `5 users` for `5 minutes`
4. `8 users` for `10 minutes`

Only move up after the prior run has:

- `0` application errors
- stable instance responsiveness
- acceptable p95 latency for `/query`

## Automated pass/fail thresholds

The locustfile sets a non-zero exit code when:

- failure ratio is above `1%`
- p95 response time is above `15000 ms`

You can override these with environment variables:

```bash
HOMEBUDDY_MAX_FAILURE_RATIO=0.02
HOMEBUDDY_P95_MS=20000
HOMEBUDDY_LOADTEST_TIMEOUT_SECONDS=240
```

## Important note about Cognito

This harness does not automate the Cognito hosted UI login flow. Instead, it
uses pre-minted bearer tokens for real beta users. That is intentional: the
goal here is to stress HomeBuddy's application path, not Cognito's login pages.
