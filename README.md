# HomeBuddy

HomeBuddy is a Streamlit + FastAPI household assistant built around a LangGraph
workflow. It answers grounded questions from uploaded documents, routes hazardous
queries to a safety flow, drafts follow-up cases and tasks, and can suggest local
contractors through Yelp.

## Current stack

- `Streamlit` frontend
- `FastAPI` backend
- `LangGraph` orchestration
- `OpenAI` models + embeddings
- `Postgres + pgvector` for document chunks and conversation history
- `SQLModel / SQLAlchemy` for app data
- `Amazon Cognito` for authentication

## Agent flow

1. `input_guardrail` sanitizes the request.
2. `orchestrator` routes to one or more specialists:
   - `troubleshooting_agent`
   - `safety_risk_agent`
   - `coverage_and_warranty_agent`
   - `home_operations_agent`
3. Specialist agents produce grounded answers and/or workflow drafts.
4. `synthesizer` merges multi-agent responses when needed.
5. `final_output_guardrail_node` checks the final response before it is returned.

## Core features

- Document upload, indexing, and deletion
- Manual-first troubleshooting with retrieval
- Coverage and warranty lookup by document type
- Deterministic safety assessment before LLM formatting
- Contractor suggestions, case drafts, and task drafts
- Cognito sign-in flow
- Conversation memory stored in Postgres
- LangSmith tracing and eval scaffolding

## Quick start

```bash
python -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## Local setup

### 1. Create `.env`

Copy `.env.example` to `.env` and fill in the values you actually need for local development.

Minimum useful local values:

- `OPENAI_API_KEY`
- `DATABASE_URL`
- `VECTOR_STORE_PROVIDER=pgvector`

Common optional values:

- `TAVILY_API_KEY`
- `YELP_API_KEY`
- Cognito values if `AUTH_DISABLED=false`
- LangSmith values if you want tracing or hosted eval runs

### 2. Start Postgres with pgvector

HomeBuddy now assumes a Postgres-backed local environment.

Example local connection string:

```bash
DATABASE_URL=postgresql+psycopg://postgres:postgres@localhost:5432/home_buddy
```

### 3. Start the backend and frontend

Run these in separate terminals:

```bash
uvicorn backend.api:app --reload
streamlit run app.py
```

The app should come up at:

- Streamlit UI: `http://localhost:8501`
- FastAPI backend: `http://localhost:8000`

## Local verification checklist

After startup, you should be able to verify:

1. The backend starts without Postgres or pgvector errors.
2. The Streamlit app loads successfully.
3. You can upload and index a document.
4. You can ask at least one troubleshooting question and get an answer.
5. You can delete a saved document and confirm it disappears from the UI.

## Environment reference

Important environment variables used by the app:

- `DATABASE_URL`
- `OPENAI_API_KEY`
- `OPENAI_MODEL`
- `TESTING_OPENAI_MODEL`
- `VECTOR_STORE_PROVIDER`
- `AUTH_DISABLED`
- `COGNITO_ISSUER`
- `COGNITO_JWKS_URL`
- `COGNITO_APP_CLIENT_ID`
- `COGNITO_APP_CLIENT_SECRET`
- `COGNITO_DOMAIN`
- `COGNITO_REDIRECT_URI`
- `COGNITO_LOGOUT_REDIRECT_URI`
- `YELP_API_KEY`
- `LANGSMITH_API_KEY`
- `LANGSMITH_PROJECT`

Notes:

- The code currently reads `COGNITO_APP_CLIENT_ID`, not `COGNITO_CLIENT_ID`.
- The app and evals both require OpenAI credentials.
- LangSmith behavior depends on your local env and API key configuration.

## Tests and evals

Run pytest:

```bash
pytest -q
```

Run all local eval suites:

```bash
python tests/run_evals.py
```

Run one suite:

```bash
python tests/run_evals.py routing
python tests/run_evals.py safety
python tests/run_evals.py grounding
python tests/run_evals.py workflow
python tests/run_evals.py correctness
```

Optional judge-based evals:

```bash
ENABLE_LLM_JUDGE_EVALS=true python tests/run_evals.py grounding
```
