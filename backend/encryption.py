"""Encryption at rest for stored document files (leases, contracts, warranties).

Document.file_bytes uses EncryptedBytes: with DOCUMENT_ENCRYPTION_KEY set, files
are written as AES-256-GCM ciphertext behind a short marker; reads decrypt
anything carrying the marker and pass older plaintext rows through unchanged,
so existing databases keep working until scripts/encrypt_documents.py runs.

Losing the key makes stored files unreadable (their search chunks still work),
so keep it with the other production secrets and back it up.
"""

import base64
import os

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from sqlalchemy import LargeBinary
from sqlalchemy.types import TypeDecorator

MARKER = b"HBENC1:"
_NONCE_BYTES = 12


class DocumentKeyError(RuntimeError):
    pass


def generate_key() -> str:
    return base64.urlsafe_b64encode(AESGCM.generate_key(bit_length=256)).decode()


def _load_key() -> bytes | None:
    raw = os.getenv("DOCUMENT_ENCRYPTION_KEY", "").strip()
    if not raw:
        return None
    try:
        key = base64.urlsafe_b64decode(raw)
    except ValueError as exc:
        raise DocumentKeyError("DOCUMENT_ENCRYPTION_KEY must be url-safe base64.") from exc
    if len(key) != 32:
        raise DocumentKeyError("DOCUMENT_ENCRYPTION_KEY must decode to 32 bytes (AES-256).")
    return key


def is_encrypted(value: bytes | None) -> bool:
    return value is not None and value.startswith(MARKER)


def encrypt(value: bytes, key: bytes) -> bytes:
    nonce = os.urandom(_NONCE_BYTES)
    return MARKER + nonce + AESGCM(key).encrypt(nonce, value, MARKER)


def decrypt(value: bytes, key: bytes | None) -> bytes:
    if key is None:
        raise DocumentKeyError("A stored document is encrypted but DOCUMENT_ENCRYPTION_KEY isn't set.")
    body = value[len(MARKER):]
    return AESGCM(key).decrypt(body[:_NONCE_BYTES], body[_NONCE_BYTES:], MARKER)


class EncryptedBytes(TypeDecorator):
    """LargeBinary that encrypts on write when a key is configured. The key is read per operation."""

    impl = LargeBinary
    cache_ok = True

    def process_bind_param(self, value, dialect):
        if value is None or is_encrypted(value):
            return value
        key = _load_key()
        return encrypt(bytes(value), key) if key else value

    def process_result_value(self, value, dialect):
        if value is None:
            return None
        value = bytes(value)
        return decrypt(value, _load_key()) if is_encrypted(value) else value
