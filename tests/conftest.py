import os


os.environ.setdefault("OPENAI_API_KEY", "test-key")
os.environ.setdefault("DATABASE_URL", "sqlite:///./test_home_buddy.db")
os.environ.setdefault("VECTOR_STORE_PROVIDER", "chroma")
os.environ["COGNITO_ALLOWED_GROUPS"] = ""
os.environ["WARM_RUNTIME_ON_STARTUP"] = "false"
os.environ["LANGCHAIN_TRACING_V2"] = "false"
os.environ["LANGCHAIN_TRACING_V2_TRACING"] = "false"
os.environ["LANGSMITH_API_KEY"] = ""
