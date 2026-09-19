"""Durable daily instance budget. Failed model attempts also consume one call."""
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

from app.core.config import get_settings
from app.core.errors import ApiError


def reserve_model_call():
    settings = get_settings()
    path = Path(settings.ai_budget_database)
    path.parent.mkdir(parents=True, exist_ok=True)
    day = datetime.now(timezone.utc).date().isoformat()
    with sqlite3.connect(path, timeout=10) as connection:
        connection.execute("CREATE TABLE IF NOT EXISTS budget (day TEXT PRIMARY KEY, calls INTEGER NOT NULL)")
        connection.execute("BEGIN IMMEDIATE")
        connection.execute("INSERT OR IGNORE INTO budget VALUES (?, 0)", (day,))
        cursor = connection.execute("UPDATE budget SET calls = calls + 1 WHERE day = ? AND calls < ?",
                                    (day, settings.ai_daily_model_call_limit))
        if cursor.rowcount != 1:
            raise ApiError(429, "MODEL_BUDGET_EXHAUSTED", "Daily model call budget exhausted; retry after UTC midnight.")
