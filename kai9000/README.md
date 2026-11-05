# KAI9000 — детерминированный MLOps-оркестратор

Источник истины: [`../Dimonich.md`](../Dimonich.md). Код в этом каталоге
вырезан из спеки скриптом `scripts/extract_dimonich_md.py` — правь спеку,
затем перезапускай экстрактор, а не наоборот.

## Архитектура

```
Termux/APK (пульт)                Redis Stream              Кластер (исполнитель)
Kai9000Agent.run_cycle()  ──►  kai9000:actions  ──►  StreamActionQueue.worker()
  метрики → пороги → действие        │                    handler: retrain/rollback/
  (детерминированно, без LLM)        │                    arch_switch/scale_up
                                     └── consumer group, pending TTL, retries
```

Пульт **решает** и пишет. Кластер **исполняет**. LLM в контуре решений нет:
иначе canary-gate и rollback теряют воспроизводимость.

## Файлы

| Файл | Роль |
|---|---|
| `kai9000/config.py` | `KAI9000Config` (pydantic-settings 2.x, префикс `KAI9000_`) |
| `kai9000/core.py` | `Kai9000Agent`: метрики → `_decide` → `_execute` → очередь |
| `kai9000/queue.py` | `StreamActionQueue`: XADD/XREADGROUP, pending TTL, retries |
| `kai9000/termux_entry.py` | вход пульта: `FileMetricsSource`/`HttpMetricsSource`, алерты |
| `cluster/handler.py` | `make_handler`: KFP retrain, KServe rollback, K8s scale |
| `cluster/worker_entry.py` | вход исполнителя: очередь + handler + graceful stop |
| `smoke.py` | смоук-прогон без Redis и кластера (фейки) |
| `apk/` | Kivy-пульт, `buildozer.spec`, foreground-сервис |
| `termux/` | `install.sh`, `tunnel.sh` |
| `cluster-checks.sh` | 4 проверки кластера перед включением воркера |
| `k8s-cluster-worker.yaml` | SA/RBAC/Deployment исполнителя |
| `k8s-networkpolicy.yaml` | egress-only политика (Redis, KFP, K8s API, DNS) |

## Запуск

### Пульт (Termux)

```bash
bash termux/install.sh
bash termux/tunnel.sh user@cluster-host          # Redis 6379 + Prometheus 9090
tmux new -s kai9000 -c ~/NeuroAgent 'python -m kai9000.termux_entry'
```

Метрики берутся из `~/.kai9000/metrics.json`, если файл есть; иначе —
из Prometheus на `127.0.0.1:9090`.

### Исполнитель (кластер)

```bash
kubectl -n ml-system create secret generic kai9000-worker \
  --from-literal=KAI9000_REDIS_URL='redis://redis:6379/0' \
  --from-literal=KUBEFLOW_TOKEN='<sa-jwt-или-oidc-токен>'
kubectl apply -f k8s-cluster-worker.yaml
./cluster-checks.sh                              # шаг 1 блокирует остальные
```

### APK

```bash
python apk/sync_core.py                          # копии kai9000/ и cluster/ в apk/
cd apk && buildozer android debug
```

Сборка на Linux-хосте: JDK 17, Android SDK/NDK (buildozer качает сам), ~15 GB.

## Образ воркера

```bash
docker build -f cluster/Dockerfile.worker \
    -t ghcr.io/dimonich9580/kai9000-worker:1.0.0 .
```

Образ slim, non-root, без curl. Проверка K8s API из пода идёт через python stdlib.

## Переменные окружения

Их два набора, и это не опечатка — так в спеке:

**1. Поля `KAI9000Config`** — префикс `Dimonich_`, регистр не важен,
незнакомые ключи игнорируются (`extra="ignore"`).

| Переменная | Дефолт |
|---|---|
| `Dimonich_DRIFT_THRESHOLD` | `0.15` |
| `Dimonich_HEALTH_THRESHOLD` | `70.0` |
| `Dimonich_COST_LIMIT_PER_HOUR_USD` | `100.0` |
| `Dimonich_MAX_RETRAIN_INTERVAL` | `6h` |
| `Dimonich_MAX_ROLLBACK_INTERVAL` | `30m` |
| `Dimonich_MIN_ACTION_INTERVAL` | `5m` |
| `Dimonich_METRICS_TTL_SECONDS` | `300` |
| `Dimonich_ROLLBACK_WINDOW` | `3` |
| `Dimonich_CANARY_TRAFFIC_RATIO` | `0.05` |
| `Dimonich_CANARY_DURATION_S` | `1800` |
| `Dimonich_DRY_RUN` | `False` — `1` включает режим без исполнения |
| `Dimonich_ALLOWED_ARCHITECTURES` | JSON-список архитектур |
| `Dimonich_ARCH_COST_PER_1K` | JSON-словарь цен |
| `Dimonich_STATE_PATH` | `~/.kai9000/state.json` (writable без root) |
| `Dimonich_ALERT_WEBHOOK` | не задан |

**2. Прямые `os.environ` в точках входа** — мимо pydantic-settings:

| Переменная | Где читается |
|---|---|
| `KAI9000_REDIS_URL` | `termux_entry.py`, `worker_entry.py` |
| `KAI9000_CONSUMER` | `worker_entry.py` (в k8s — имя пода) |
| `KAI9000_CLUSTER_ALERT_WEBHOOK` | `worker_entry.py` |
| `KUBEFLOW_URL`, `KUBEFLOW_TOKEN` | `worker_entry.py` → KFP retrain |
| `KUBE_API_URL` | `worker_entry.py` → `_k8s_scale` |
| `KSERVE_NAMESPACE`, `KSERVE_ISVC` | `worker_entry.py` → rollback |
| `SCALE_TARGET_KIND`, `SCALE_TARGET_NAME` | `worker_entry.py` → scale |

Расхождение в самой спеке: docstring `config.py` называет `Dimonich_REDIS_URL`
и `Dimonich_CONSUMER` «обычными env-переменными», а код читает
`KAI9000_REDIS_URL` и `KAI9000_CONSUMER`. Работает код — он и задокументирован.

## Проверки

```bash
python smoke.py            # SMOKE OK — без Redis и кластера
python -m py_compile kai9000/*.py cluster/*.py smoke.py
./cluster-checks.sh --only 2
```

`cluster-checks.sh`: шаг 1 — pydantic-settings 2.x (блокирующий), шаг 2 —
KFP API 200, шаг 3 — HPA против воркера (ветки A/B/C), шаг 4 — NetworkPolicy
и связность (PONG + 200 + 200).

## Платформенная заметка

`_LockedState` использует `fcntl.flock` на POSIX (Termux, кластер) и
`msvcrt.locking` на Windows — только чтобы смоук и юнит-прогон шли на
dev-машине. Поведение на целевых платформах не меняется.
