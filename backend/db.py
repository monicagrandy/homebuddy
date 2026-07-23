from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, sessionmaker

from backend.config import settings, bool_env

# 1. Engine — SQLite by default, Postgres once DATABASE_URL is configured
engine = create_engine(settings.database_url, echo= bool_env("SQL_ECHO", False), pool_pre_ping=True)
# 2. Session factory
SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False)
# 3. Base class — imported by models.py to define tables
class Base(DeclarativeBase):
    pass
# 4. FastAPI dependency — route handlers call this via Depends()
def get_session():
    with SessionLocal() as session:
        yield session
