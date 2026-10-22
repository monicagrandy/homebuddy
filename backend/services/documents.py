"""Document identity: entry ids, content hashes and duplicate detection."""

import hashlib
import re

from sqlalchemy import select
from sqlalchemy.orm import Session

from backend.models import Document


def slugify(display_name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", display_name.lower()).strip("-") or "document"


def allocate_entry_id(session: Session, *, household_id: int, display_name: str) -> str:
    """A slug of the display name that's unused in the household (`manual`, `manual-2`, ...).

    Matches the slugs the web app has always generated (app.py build_entry_id).
    """
    slug = slugify(display_name)
    taken = set(
        session.scalars(
            select(Document.entry_id).where(
                Document.household_id == household_id,
                (Document.entry_id == slug) | Document.entry_id.like(f"{slug}-%"),
            )
        )
    )
    candidate = slug
    suffix = 2
    while candidate in taken:
        candidate = f"{slug}-{suffix}"
        suffix += 1
    return candidate


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def find_duplicate(
    session: Session,
    *,
    household_id: int,
    content_sha256: str | None = None,
    source_url: str | None = None,
) -> Document | None:
    """An existing document in the household with the same file contents or the same URL."""
    if content_sha256:
        match = session.scalar(
            select(Document)
            .where(Document.household_id == household_id, Document.content_sha256 == content_sha256)
            .order_by(Document.id)
            .limit(1)
        )
        if match is not None:
            return match
    if source_url:
        return session.scalar(
            select(Document)
            .where(Document.household_id == household_id, Document.source_url == source_url)
            .order_by(Document.id)
            .limit(1)
        )
    return None
