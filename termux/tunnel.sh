#!/data/data/com.termux/files/usr/bin/bash
# termux/tunnel.sh — SSH-туннель к Redis Stream и Prometheus кластера.
# Запуск: bash termux/tunnel.sh [user@cluster-host]
set -euo pipefail

TARGET="${1:-user@cluster-host}"
REDIS_LOCAL="${REDIS_LOCAL:-6379}"
REDIS_REMOTE="${REDIS_REMOTE:-redis:6379}"
PROM_LOCAL="${PROM_LOCAL:-9090}"
PROM_REMOTE="${PROM_REMOTE:-prometheus:9090}"

# ExitOnForwardFailure: не оставляем полумёртвый туннель без портов.
# ServerAlive*: Termux усыпляет сокеты — keepalive держит канал живым.
exec ssh -N \
    -o ExitOnForwardFailure=yes \
    -o ServerAliveInterval=30 \
    -o ServerAliveCountMax=3 \
    -o TCPKeepAlive=yes \
    -L "127.0.0.1:${REDIS_LOCAL}:${REDIS_REMOTE}" \
    -L "127.0.0.1:${PROM_LOCAL}:${PROM_REMOTE}" \
    "${TARGET}"
