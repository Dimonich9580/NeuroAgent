#!/usr/bin/env bash
# scripts/termux-smoke.sh — прогон smoke на Termux-устройстве.
# Запуск внутри Termux:
#   bash scripts/termux-smoke.sh
# Не требует кластера. Проверяет fcntl-путь блокировки состояния
# и публичный run() с фейковыми источниками.
set -euo pipefail

step() { printf '\n[termux] %s\n' "$*"; }

step "0. Проверка окружения"
command -v python >/dev/null || { echo "python не установлен: pkg install python"; exit 1; }
python -c "import fcntl; print('[termux] fcntl доступен')" || {
    echo "[termux][FAIL] fcntl недоступен — это не Termux/POSIX"
    exit 1
}

step "1. py_compile всех модулей"
python -m py_compile \
    kai9000/config.py kai9000/core.py kai9000/queue.py \
    kai9000/termux_entry.py kai9000/__init__.py \
    cluster/handler.py cluster/worker_entry.py cluster/__init__.py \
    smoke.py
echo "[termux] compile ok"

step "2. smoke.py"
python smoke.py

step "3. termux_entry: реальный вход, 6 секунд, стоп"
python - <<'PY'
import asyncio, json, os, pathlib
home = pathlib.Path.home() / ".kai9000"
created = not home.exists()
home.mkdir(exist_ok=True)
(home / "metrics.json").write_text(json.dumps({
    "model_id": "prod-model", "accuracy": 0.91, "latency_p99_ms": 48,
    "cost_per_1k_usd": 0.07, "drift_score": 0.12,
}))
os.environ["Dimonich_DRY_RUN"] = "1"
os.environ.pop("KAI9000_REDIS_URL", None)

from kai9000 import termux_entry

async def run():
    t = asyncio.create_task(termux_entry.main())
    await asyncio.sleep(6)
    t.cancel()
    try:
        await t
    except asyncio.CancelledError:
        pass

asyncio.run(run())
state = json.loads((home / "state.json").read_text())
history = [a["action_type"] for a in state.get("action_history", [])]
print("[termux] state history:", history)
assert history, "action_history пуст — цикл не отработал"
if created:
    import shutil; shutil.rmtree(home)
PY

echo
echo "[termux] готово: fcntl-путь, smoke, реальный вход — всё зелёное."
echo "[termux] следующий шаг на устройстве:"
echo "  bash termux/tunnel.sh user@cluster-host &"
echo "  export KAI9000_REDIS_URL=redis://127.0.0.1:6379/0"
echo "  tmux new -s kai9000 -c ~/NeuroAgent 'python -m kai9000.termux_entry'"
