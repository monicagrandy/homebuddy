import logging
import os
import sys
from functools import cached_property

from dotenv import load_dotenv
from langchain_openai import ChatOpenAI
from openai import OpenAI

load_dotenv()

def get_logger(name: str) -> logging.Logger:
    """Create a module-level logger with a readable format."""
    logger = logging.getLogger(name)
    if not logger.handlers:
        handler = logging.StreamHandler(sys.stdout)
        handler.setFormatter(
            logging.Formatter("%(asctime)s | %(name)-18s | %(levelname)-7s | %(message)s",
                              datefmt="%H:%M:%S")
        )
        logger.addHandler(handler)
    logger.setLevel(logging.INFO)
    return logger

logger = get_logger(__name__)

def bool_env(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _int_env(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None:
        return default
    try:
        return int(value)
    except ValueError:
        return default


def _csv_env(name: str) -> tuple[str, ...]:
    value = os.getenv(name)
    if not value:
        return tuple()
    return tuple(dict.fromkeys(item.strip() for item in value.split(",") if item.strip()))

class Settings:
    openai_model = os.getenv("OPENAI_MODEL", "gpt-4o")
    testing_openai_model = (
        os.getenv("OPENAI_TESTING_MODEL")
        or os.getenv("TESTING_OPENAI_MODEL")
        or "gpt-4o-mini"
    )
    moderation_model = os.getenv("OPENAI_MODERATION_MODEL", "omni-moderation-latest")
    openai_key = os.getenv("OPENAI_API_KEY") or os.getenv("OPENAI_KEY", "")
    database_url = os.getenv("DATABASE_URL", "postgresql+psycopg://postgres:postgres@localhost:5432/home_buddy")
    cognito_region = os.getenv("COGNITO_REGION", "")
    cognito_user_pool_id = os.getenv("COGNITO_USER_POOL_ID", "")
    cognito_app_client_id = os.getenv("COGNITO_APP_CLIENT_ID", "")
    cognito_issuer = os.getenv("COGNITO_ISSUER", "")
    cognito_jwks_url = os.getenv("COGNITO_JWKS_URL", "")
    cognito_app_client_secret = os.getenv("COGNITO_APP_CLIENT_SECRET")
    cognito_domain = os.getenv("COGNITO_DOMAIN", "")
    cognito_redirect_uri = os.getenv("COGNITO_REDIRECT_URI", "")
    cognito_logout_redirect_uri = os.getenv("COGNITO_LOGOUT_REDIRECT_URI", "")
    cognito_allowed_groups = _csv_env("COGNITO_ALLOWED_GROUPS")
    presidio_spacy_model = os.getenv("PRESIDIO_SPACY_MODEL", "en_core_web_sm")
    warm_runtime_on_startup = bool_env("WARM_RUNTIME_ON_STARTUP", True)
    vector_store_provider = os.getenv("VECTOR_STORE_PROVIDER", "pgvector")
    chroma_db_dir = os.getenv("CHROMA_DB_DIR", "chroma_db")
    embedding_dimensions = _int_env("EMBEDDING_DIMENSIONS", 1536)
    yelp_api_key = os.getenv("YELP_API_KEY", "")
    yelp_api_url = os.getenv("YELP_API_URL", "https://api.yelp.com/ai/chat/v2")
    langsmith_tracing = bool_env("LANGCHAIN_TRACING_V2", True)
    contractor_suggestion_limit = _int_env("CONTRACTOR_SUGGESTION_LIMIT", 3)

    @cached_property
    def llm(self) -> ChatOpenAI:
        return ChatOpenAI(
            model=self.openai_model,
            temperature=0.3,
            api_key=self.openai_key,
        )

    @cached_property
    def judge_llm(self) -> ChatOpenAI:
        return ChatOpenAI(
            model=self.testing_openai_model,
            temperature=0.0,
            api_key=self.openai_key,
        )

    @cached_property
    def openai_client(self) -> OpenAI:
        return OpenAI(api_key=self.openai_key)

settings = Settings()
