#!/data/data/com.termux/files/usr/bin/bash
# termux/install.sh — установка пульта KAI9000 в Termux.
# Запуск: bash termux/install.sh   (из корня проекта)
set -euo pipefail

pkg update -y && pkg upgrade -y
pkg install -y python python-pip python-numpy git openssh tmux termux-api \
    clang make libffi openssl rust binutils

pip install --upgrade pip
pip install httpx pydantic pydantic-settings redis

mkdir -p "$HOME/.kai9000"
if [ ! -f "$HOME/.kai9000/metrics.json" ]; then
    cat > "$HOME/.kai9000/metrics.json" <<'EOF'
{"model_id":"prod-model","accuracy":0.91,"latency_p99_ms":48,
 "cost_per_1k_usd":0.07,"drift_score":0.12}
EOF
fi

termux-wake-lock

cat <<'MSG'
Установлено.

Дальше:
  1) туннель:   bash termux/tunnel.sh user@cluster-host
  2) агент:     tmux new -s kai9000 -c ~/NeuroAgent 'python -m kai9000.termux_entry'

Файл метрик ~/.kai9000/metrics.json читается, если существует.
Иначе источник — Prometheus на 127.0.0.1:9090 (через туннель).
MSG
