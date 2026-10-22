"""Encrypt stored document files that were saved before DOCUMENT_ENCRYPTION_KEY was set.

Run once after adding the key, from the repo root (or inside the backend container):

    python scripts/encrypt_documents.py            # report what would change
    python scripts/encrypt_documents.py --apply    # encrypt them

Generate a key with: python -c "from backend.encryption import generate_key; print(generate_key())"
"""

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from sqlalchemy import select, text  # noqa: E402
from sqlalchemy.orm.attributes import flag_modified  # noqa: E402

from backend.db import SessionLocal  # noqa: E402
from backend.encryption import _load_key, is_encrypted  # noqa: E402
from backend.models import Document  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apply", action="store_true", help="encrypt the files (default: dry run)")
    args = parser.parse_args()

    if _load_key() is None:
        print("DOCUMENT_ENCRYPTION_KEY isn't set; nothing to encrypt with.", file=sys.stderr)
        return 1

    with SessionLocal() as session:
        ids = list(session.scalars(select(Document.id).where(Document.file_bytes.is_not(None)).order_by(Document.id)))
        plaintext = []
        for document_id in ids:
            # Read the raw column so already-encrypted rows are skipped without decrypting.
            raw = session.execute(text("SELECT file_bytes FROM documents WHERE id = :id"), {"id": document_id}).scalar_one()
            if not is_encrypted(bytes(raw)):
                plaintext.append(document_id)

        print(f"{len(ids)} stored files, {len(plaintext)} not yet encrypted.")
        if not args.apply:
            return 0
        for document_id in plaintext:
            document = session.get(Document, document_id)
            flag_modified(document, "file_bytes")  # rewrite through EncryptedBytes
            session.commit()
            session.expunge(document)  # files can be large; don't keep them in memory
        print(f"Encrypted {len(plaintext)} files.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
