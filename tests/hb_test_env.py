"""Environment for tests and contract export. Import before anything from backend."""

import os

os.environ.setdefault("OPENAI_API_KEY", "test-key")
os.environ.setdefault("DATABASE_URL", "sqlite:///./test_home_buddy.db")
os.environ.setdefault("VECTOR_STORE_PROVIDER", "chroma")
os.environ.setdefault("CONTRACTOR_SEARCH_PROVIDER", "mock")
os.environ.setdefault("HOME_OPERATIONS_ENABLED", "true")
os.environ["COGNITO_ISSUER"] = "https://cognito-idp.us-east-2.amazonaws.com/us-east-2_testpool"
os.environ["COGNITO_JWKS_URL"] = "https://cognito-idp.us-east-2.amazonaws.com/us-east-2_testpool/.well-known/jwks.json"
os.environ["COGNITO_APP_CLIENT_ID"] = "test-web-client"
os.environ["COGNITO_ALLOWED_GROUPS"] = ""
os.environ["WARM_RUNTIME_ON_STARTUP"] = "false"
os.environ["LANGCHAIN_TRACING_V2"] = "false"
os.environ["LANGCHAIN_TRACING_V2_TRACING"] = "false"
os.environ["LANGSMITH_API_KEY"] = ""
os.environ["ENVIRONMENT"] = "test"
os.environ["ALLOWED_HOSTS"] = ""
# Rate limits are off unless a test turns them on (tests/api/test_rate_limit.py).
os.environ["RATE_LIMIT_QUERIES_PER_MINUTE"] = "0"
os.environ["RATE_LIMIT_DOCUMENTS_PER_MINUTE"] = "0"
os.environ["DEMO_HOUSEHOLD_ID"] = "0"
os.environ["DEMO_QUERIES_PER_MINUTE"] = "0"
os.environ["DEMO_QUERIES_PER_VISITOR_PER_DAY"] = "0"
os.environ["DEMO_QUERIES_PER_DAY"] = "0"
# Tests run indexing jobs explicitly with DocumentJobRunner.run_next().
os.environ["DOCUMENT_JOBS_ENABLED"] = "false"
os.environ["MAINTENANCE_ENABLED"] = "false"
