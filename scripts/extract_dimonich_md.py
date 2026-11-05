"""Извлекает код проекта KAI9000 из Dimonich.md в файлы репозитория.

Dimonich.md — источник истины: секции 4.1–4.7 содержат файлы целиком.
Скрипт режет по номерам строк и убирает markdown-артефакты (```python, python,
bash), которые в спеке стоят без закрывающих fences.

Запуск из корня проекта:  python scripts/extract_dimonich_md.py
"""

from __future__ import annotations

import pathlib

REPO = pathlib.Path(__file__).resolve().parent.parent
MD = REPO / "Dimonich.md"

# (start_line, end_line) включительно, 1-based — границы секций в Dimonich.md
SECTIONS = {
    "kai9000/config.py": (81, 134),
    "kai9000/core.py": (137, 706),
    "kai9000/queue.py": (709, 911),
    "kai9000/termux_entry.py": (914, 1148),
    "cluster/handler.py": (1151, 1446),
    "cluster/worker_entry.py": (1449, 1507),
    "smoke.py": (1510, 1589),
}

FENCE = {"```", "```python", "```bash", "python", "bash", "text"}


def main() -> None:
    lines = MD.read_text(encoding="utf-8").splitlines()
    for rel, (start, end) in SECTIONS.items():
        cleaned: list[str] = []
        dropped: list[tuple[int, str]] = []
        for lineno, line in zip(range(start, end + 1), lines[start - 1:end]):
            if line.strip() in FENCE:
                dropped.append((lineno, line))
                continue
            cleaned.append(line)
        while cleaned and not cleaned[-1].strip():
            cleaned.pop()
        target = REPO / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("\n".join(cleaned) + "\n", encoding="utf-8")
        print(f"wrote {rel}: {len(cleaned)} lines")
        for lineno, line in dropped:
            print(f"  dropped artifact line {lineno}: {line!r}")


if __name__ == "__main__":
    main()
