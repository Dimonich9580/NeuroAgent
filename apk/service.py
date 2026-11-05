"""Точка входа foreground-сервиса (без Kivy UI).

p4a запускает этот файл в отдельном процессе `:service`. Настройки берутся
из ~/.kai9000/apk.json — тот же файл, что пишет пульт. Дальше путь один:
kai9000.termux_entry.main(), чтобы сервис и Termux вели себя одинаково.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
from pathlib import Path

CONFIG_PATH = Path.home() / ".kai9000" / "apk.json"


def _bootstrap_env() -> None:
    try:
        cfg = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        cfg = {}
    url = (cfg.get("redis_url") or "").strip()
    if url and not os.environ.get("KAI9000_REDIS_URL"):
        os.environ["KAI9000_REDIS_URL"] = url
    if cfg.get("dry_run") and not os.environ.get("Dimonich_DRY_RUN"):
        os.environ["Dimonich_DRY_RUN"] = "1"


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    _bootstrap_env()
    from kai9000.termux_entry import main as agent_main

    asyncio.run(agent_main())


if __name__ == "__main__":
    main()
