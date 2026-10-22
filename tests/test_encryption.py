import importlib.util
from pathlib import Path

import pytest
from cryptography.exceptions import InvalidTag
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker

from backend import encryption
from backend.db import Base
from backend.encryption import MARKER, DocumentKeyError, generate_key
from backend.models import Document, Household

PDF = b"%PDF-1.4 lease agreement for 12 Elm St"


@pytest.fixture
def db(tmp_path):
    engine = create_engine(f"sqlite:///{tmp_path / 'enc.db'}")
    Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, autoflush=False, autocommit=False)
    with factory() as session:
        session.add(Household(name="Home", zip_code="80202", home_type="condo"))
        session.commit()
    yield factory
    engine.dispose()


def _store(factory, data: bytes = PDF, entry_id: str = "lease") -> int:
    with factory() as session:
        document = Document(entry_id=entry_id, household_id=1, doc_type="lease", source_name="Lease", file_bytes=data)
        session.add(document)
        session.commit()
        return document.id


def _raw(factory, document_id: int) -> bytes:
    with factory() as session:
        return bytes(session.execute(text("SELECT file_bytes FROM documents WHERE id = :id"), {"id": document_id}).scalar_one())


def _read(factory, document_id: int) -> bytes:
    with factory() as session:
        return session.get(Document, document_id).file_bytes


def test_files_are_encrypted_at_rest_when_a_key_is_set(db, monkeypatch):
    monkeypatch.setenv("DOCUMENT_ENCRYPTION_KEY", generate_key())

    document_id = _store(db)

    raw = _raw(db, document_id)
    assert raw.startswith(MARKER)
    assert b"lease agreement" not in raw
    assert _read(db, document_id) == PDF


def test_files_are_stored_as_is_without_a_key(db, monkeypatch):
    monkeypatch.delenv("DOCUMENT_ENCRYPTION_KEY", raising=False)

    document_id = _store(db)

    assert _raw(db, document_id) == PDF
    assert _read(db, document_id) == PDF


def test_older_plaintext_files_stay_readable_after_a_key_is_added(db, monkeypatch):
    monkeypatch.delenv("DOCUMENT_ENCRYPTION_KEY", raising=False)
    document_id = _store(db)
    monkeypatch.setenv("DOCUMENT_ENCRYPTION_KEY", generate_key())

    assert _read(db, document_id) == PDF


def test_an_encrypted_file_without_the_key_fails_loudly(db, monkeypatch):
    monkeypatch.setenv("DOCUMENT_ENCRYPTION_KEY", generate_key())
    document_id = _store(db)
    monkeypatch.delenv("DOCUMENT_ENCRYPTION_KEY")

    with pytest.raises(DocumentKeyError):
        _read(db, document_id)


def test_the_wrong_key_or_tampering_is_detected(db, monkeypatch):
    key = generate_key()
    monkeypatch.setenv("DOCUMENT_ENCRYPTION_KEY", key)
    raw = encryption.encrypt(PDF, encryption._load_key())
    tampered = raw[:-1] + bytes([raw[-1] ^ 1])

    with pytest.raises(InvalidTag):
        encryption.decrypt(tampered, encryption._load_key())
    with pytest.raises(InvalidTag):
        encryption.decrypt(raw, encryption.AESGCM.generate_key(bit_length=256))


@pytest.mark.parametrize("bad_key", ["not base64!!", "c2hvcnQ="])
def test_malformed_keys_are_rejected(monkeypatch, bad_key):
    monkeypatch.setenv("DOCUMENT_ENCRYPTION_KEY", bad_key)

    with pytest.raises(DocumentKeyError):
        encryption._load_key()


def _load_script():
    path = Path(__file__).resolve().parent.parent / "scripts" / "encrypt_documents.py"
    spec = importlib.util.spec_from_file_location("encrypt_documents_script", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_the_backfill_script_encrypts_older_files(db, monkeypatch, capsys):
    monkeypatch.delenv("DOCUMENT_ENCRYPTION_KEY", raising=False)
    first = _store(db, entry_id="lease")
    second = _store(db, entry_id="warranty")
    monkeypatch.setenv("DOCUMENT_ENCRYPTION_KEY", generate_key())
    script = _load_script()
    monkeypatch.setattr(script, "SessionLocal", db)
    monkeypatch.setattr("sys.argv", ["encrypt_documents.py"])

    assert script.main() == 0
    assert "2 not yet encrypted" in capsys.readouterr().out
    assert not _raw(db, first).startswith(MARKER)

    monkeypatch.setattr("sys.argv", ["encrypt_documents.py", "--apply"])
    assert script.main() == 0

    assert _raw(db, first).startswith(MARKER) and _raw(db, second).startswith(MARKER)
    assert _read(db, first) == PDF
    monkeypatch.setattr("sys.argv", ["encrypt_documents.py"])
    script.main()
    assert "0 not yet encrypted" in capsys.readouterr().out
