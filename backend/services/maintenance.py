"""Daily housekeeping in a background thread: conversation retention and old job cleanup."""

import threading
from collections.abc import Callable

from backend.config import get_logger

logger = get_logger(__name__)

DAY_SECONDS = 24 * 60 * 60


class MaintenanceWorker:
    def __init__(self, tasks: dict[str, Callable[[], int]], *, interval_seconds: float = DAY_SECONDS) -> None:
        self._tasks = tasks
        self._interval = interval_seconds
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def run_once(self) -> dict[str, int]:
        results: dict[str, int] = {}
        for name, task in self._tasks.items():
            try:
                results[name] = task()
            except Exception:
                logger.exception("Maintenance task %s failed", name)
        if any(results.values()):
            logger.info("Maintenance removed %s", results)
        return results

    def start(self) -> None:
        self._thread = threading.Thread(target=self._loop, name="maintenance", daemon=True)
        self._thread.start()

    def stop(self, timeout: float = 5.0) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout)

    def _loop(self) -> None:
        while not self._stop.is_set():
            self.run_once()
            self._stop.wait(self._interval)
