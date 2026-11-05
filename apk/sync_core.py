"""Копирует ядро KAI9000 в apk/ перед сборкой buildozer.

buildozer пакует только source.dir (apk/), а kai9000/ и cluster/ лежат
уровнем выше. Скрипт делает сборку самодостаточной и не требует symlink
(на Windows их нет).

Запуск:  python apk/sync_core.py     (из корня проекта)
Перед сборкой apk/ содержит копии; они в .gitignore.
"""

from __future__ import annotations

import shutil
from pathlib import Path

APK = Path(__file__).resolve().parent
REPO = APK.parent
PACKAGES = ("kai9000", "cluster")


def main() -> None:
    for name in PACKAGES:
        src = REPO / name
        dst = APK / name
        if not src.is_dir():
            raise SystemExit(f"missing package: {src}")
        if dst.exists():
            shutil.rmtree(dst)
        shutil.copytree(
            src, dst,
            ignore=shutil.ignore_patterns("__pycache__", "*.pyc", "*.pyo"),
        )
        print(f"synced {name} -> {dst.relative_to(REPO)}")


if __name__ == "__main__":
    main()
