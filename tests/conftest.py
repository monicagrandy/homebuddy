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
