"""Shared fixtures for API tests: a TestClient on a fresh SQLite database per test."""

from collections.abc import Generator

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from backend import api
from backend.auth import AuthenticatedIdentity
from backend.db import Base
from backend.rate_limit import limiter


@pytest.fixture(autouse=True)
def reset_rate_limiter() -> Generator[None, None, None]:
    limiter.reset()
    yield
    limiter.reset()


@pytest.fixture(autouse=True)
def clear_dependency_overrides() -> Generator[None, None, None]:
    api.app.dependency_overrides.clear()
    yield
    api.app.dependency_overrides.clear()


@pytest.fixture
def client(tmp_path, monkeypatch) -> Generator[TestClient, None, None]:
    db_path = tmp_path / "api_test.db"
    engine = create_engine(
        f"sqlite:///{db_path}",
        connect_args={"check_same_thread": False},
    )
    testing_session_local = sessionmaker(bind=engine, autoflush=False, autocommit=False)
    Base.metadata.create_all(engine)

    monkeypatch.setattr(api, "engine", engine)
    monkeypatch.setattr(api, "SessionLocal", testing_session_local)
    monkeypatch.setattr(api.settings, "database_url", f"sqlite:///{db_path}")
    monkeypatch.setattr(api.settings, "warm_runtime_on_startup", False)

    def override_get_session():
        with testing_session_local() as session:
            yield session

    api.app.dependency_overrides[api.get_session] = override_get_session
    api.app.dependency_overrides[api.get_current_identity] = lambda: AuthenticatedIdentity(
        subject="dev-user-sub",
        email="dev@example.com",
        display_name="Dev User",
    )

    with TestClient(api.app) as test_client:
        test_client.testing_session_local = testing_session_local
        yield test_client

    engine.dispose()


