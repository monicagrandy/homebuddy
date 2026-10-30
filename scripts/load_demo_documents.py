"""Index the public manuals the homebuddy.chat demo answers from.

The demo household has no members, so only the public /demo routes can read it. Run from the
repo root (or inside the backend container):

    python scripts/load_demo_documents.py                              # report what's missing
    python scripts/load_demo_documents.py --create-household --apply   # first time: prints the id
    python scripts/load_demo_documents.py --apply                      # index missing manuals

Then set DEMO_HOUSEHOLD_ID to the household id. Each manual is downloaded from the
manufacturer, indexed like a link added in the app, and linked back to the manufacturer's copy.
Rerunning only adds manuals that aren't indexed yet.
"""

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from sqlalchemy import select  # noqa: E402

from backend.config import settings  # noqa: E402
from backend.db import SessionLocal  # noqa: E402
from backend.dependencies import get_ingestion_service  # noqa: E402
from backend.models import Document, Household  # noqa: E402

DEMO_DOCUMENTS = [
    {
        "entry_id": "bosch-dishwasher-manual",
        "display_name": "Bosch dishwasher use & care manual",
        "doc_type": "manual",
        "url": "https://media3.bosch-home.com/Documents/9000633149_A.pdf",
    },
    {
        "entry_id": "rheem-gas-water-heater-manual",
        "display_name": "Rheem gas water heater use & care manual",
        "doc_type": "manual",
        "url": "https://files.myrheem.com/webpartners/ProductDocuments/97C49E08-82B8-42BE-AFFB-13D865798F9D.pdf",
    },
    {
        "entry_id": "rheem-water-heater-warranty",
        "display_name": "Rheem water heater limited warranty",
        "doc_type": "warranty",
        "url": "https://files.myrheem.com/webpartners/ProductDocuments/6AB32DB9-A154-4181-9036-6CBA91098627.pdf",
    },
]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--create-household", action="store_true", help="create the demo household first")
    parser.add_argument("--apply", action="store_true", help="make the changes (default: dry run)")
    args = parser.parse_args()

    with SessionLocal() as session:
        if args.create_household:
            if settings.demo_household_id:
                print(f"DEMO_HOUSEHOLD_ID is already {settings.demo_household_id}; not creating another.", file=sys.stderr)
                return 1
            if not args.apply:
                print("Would create the demo household.")
                return 0
            household = Household(name="Home Buddy demo", zip_code="80202", home_type="house")
            session.add(household)
            session.commit()
            print(f"Created the demo household. Set DEMO_HOUSEHOLD_ID={household.id}")
        else:
            household = session.get(Household, settings.demo_household_id) if settings.demo_household_id else None
            if household is None:
                print("Set DEMO_HOUSEHOLD_ID, or pass --create-household.", file=sys.stderr)
                return 1

        indexed = set(session.scalars(select(Document.entry_id).where(Document.household_id == household.id)))
        missing = [item for item in DEMO_DOCUMENTS if item["entry_id"] not in indexed]
        print(f"{len(DEMO_DOCUMENTS) - len(missing)} demo manuals indexed, {len(missing)} missing.")
        if not args.apply:
            for item in missing:
                print(f"  would index {item['display_name']} from {item['url']}")
            return 0

        service = get_ingestion_service()
        for item in missing:
            result = service.index(
                household_id=household.id,
                entry_id=item["entry_id"],
                session_id="demo",
                doc_type=item["doc_type"],
                url=item["url"],
            )
            session.add(
                Document(
                    entry_id=item["entry_id"],
                    household_id=household.id,
                    doc_type=item["doc_type"],
                    source_name=item["display_name"],
                    source_url=item["url"],
                )
            )
            session.commit()
            print(f"  indexed {item['display_name']} ({result['chunks_indexed']} chunks)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
