# Dimonich — автономный MLOps-оркестратор

**Версия:** 1.0 · **Статус:** код закрыт, готов к прогону на кластере
**Стек:** Python 3.11+, pydantic v2, pydantic-settings v2, httpx, redis-py, Kivy (APK-пульт)

---

## 1. Что это

Автономный агент, который живёт рядом с ML-инфраструктурой и принимает
решения о переконфигурации пайплайнов в реальном времени. Два контура:

**Пульт (телефон, Termux или APK).** Читает метрики, оценивает дрейф,
принимает решение по детерминированным порогам, пишет в Redis Stream.
Не исполняет — отправляет.

**Исполнитель (кластер).** Читает Redis Stream, вызывает KFP / KServe /
K8s API, подтверждает через XACK. Не решает — исполняет.

Три роли, по одному экземпляру на процесс:
- `Dimonich Agent` — решатель, один на устройство.
- `StreamActionWorker` — исполнитель, один под (replicas=1) или N подов
  с consumer group.
- `handler` — KFP/KServe-адаптер внутри воркера.

---

## 2. Архитектура
Телефон (Termux/APK) Кластер
┌──────────────────────┐ ┌──────────────────────────┐
│ DimonichAgent │ │ StreamActionWorker │
│ _cycle → _decide │ │ XREADGROUP → handler │
│ _execute │ │ → KFP run / KServe │
│ state.json (flock) │ │ → XACK │
│ _send_alert (phone) │ │ _send_cluster_alert │
└──────────┬───────────┘ └────────────▲─────────────┘
│ XADD │ XREADGROUP
└─── SSH-туннель ──→ Redis Stream ←──────┘
kai9000:actions

Idempotency: Redis SET NX EX по action_id (pending → done)
Каналы alert: телефонный (через туннель) + кластерный (из пода)

text

**Ключевые инварианты:**
- Решения детерминированные (`_decide` по порогам, не по вероятностям).
  LLM в контуре решений нет и не должно быть — иначе canary-gate и
  rollback перестают быть воспроизводимыми.
- Телефонный alert идёт через SSH-туннель, кластерный — из пода.
  Два независимых канала на разные классы инцидентов.
- Идемпотентность в воркере: `action_id` → `SET NX EX` в Redis.
  Повтор после `XAUTOCLAIM` не запускает второе действие.

---

## 3. Файлы

| Файл | Роль |
|------|------|
| `Dimonich/config.py` | `KAI9000Config` (BaseSettings, extra="ignore") |
| `Dimonich/core.py` | `Kai9000Agent`, `MetricsSource`, `ModelMetrics`, `_LockedState` |
| `Dimonich/queue.py` | `StreamActionQueue` (писатель) + `StreamActionWorker` (читатель) |
| `Dimonich/termux_entry.py` | точка входа Termux + `FileMetricsSource` + `HttpMetricsSource` + alert-throttle |
| `cluster/handler.py` | `HandlerDeps`, `make_handler`, KFP/KServe/scale, alert |
| `cluster/worker_entry.py` | точка входа воркера, `on_shutdown=handler.aclose` |
| `smoke.py` | локальный смоук публичного `run()` |
| `cluster-checks.sh` | 4 кластерные проверки |
| `apk/main.py` | Kivy-пульт (APK) |
| `apk/src/org/Dimonich/service/PythonService.java` | foreground-service |
| `apk/buildozer.spec` | конфиг p4a |
| `termux/install.sh`, `termux/tunnel.sh` | установка Termux и SSH-туннель |

---

## 4. Код

### 4.1 `Dimonich/config.py`

```python
from __future__ import annotations

from datetime import timedelta
from pathlib import Path
from typing import Optional

from pydantic_settings import BaseSettings, SettingsConfigDict


class KAI9000Config(BaseSettings):
    """Конфиг KAI9000. Читается из окружения с префиксом Dimonich_.

    Незнакомые KAI9000_* переменные игнорируются (extra="ignore"):
    Dimonich_REDIS_URL, Dimonich_CONSUMER и подобные остаются обычными
    env-переменными для termux_entry/worker_entry и в конфиг не попадают.
    """

    model_config = SettingsConfigDict(
        env_prefix="Dimonich_",
        extra="ignore",
        case_sensitive=False,
    )

    drift_threshold: float = 0.15
    health_threshold: float = 70.0
    cost_limit_per_hour_usd: float = 100.0

    max_retrain_interval: timedelta = timedelta(hours=6)
    max_rollback_interval: timedelta = timedelta(minutes=30)
    min_action_interval: timedelta = timedelta(minutes=5)

    metrics_ttl_seconds: int = 300

    rollback_window: int = 3
    canary_traffic_ratio: float = 0.05
    canary_duration_s: int = 1800

    dry_run: bool = False

    allowed_architectures: list[str] = [
        "xgboost",
        "lightgbm",
        "transformer_small",
        "transformer_large",
    ]
    arch_cost_per_1k: dict[str, float] = {
        "xgboost": 0.010,
        "lightgbm": 0.015,
        "transformer_small": 0.050,
        "transformer_large": 0.200,
    }

    state_path: Path = Path("/var/lib/Dimonich/state.json")
    alert_webhook: Optional[str] = None
4.2 kai9000/core.py
python
# kai9000/core.py — финал
#
# Патчи, сведённые в этом файле:
#   • KAI9000Config импортируется из kai9000.config (единственное
#     объявление класса живёт там, с extra="ignore")
#   • StreamActionQueue под TYPE_CHECKING: redis не тянется в Termux,
#     пока очередь не подключена
#   • attach_queue + закрытие очереди в run().finally
#   • в _execute — enqueue после записи в action_history, перед persist
#   • _decide: при perf_degradation и исчерпанном _better_arch
#     (None = «лучше некуда») — эскалация в ALERT вместо молчания
#   • _execute: ветка ALERT вызывает _send_alert
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import signal
import tempfile
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from enum import Enum
from pathlib import Path
from typing import TYPE_CHECKING, Callable, Optional, Protocol

from pydantic import BaseModel

from kai9000.config import KAI9000Config

if TYPE_CHECKING:  # без жёсткой зависимости от redis в Termux
    from kai9000.queue import StreamActionQueue

log = logging.getLogger("kai9000")


def _sha(s: str) -> str:
    return hashlib.sha256(s.encode()).hexdigest()[:16]


class DriftSeverity(Enum):
    NONE = 0
    LOW = 1
    MEDIUM = 2
    HIGH = 3
    CRITICAL = 4


class ActionType(str, Enum):
    RETRAIN = "retrain"
    ROLLBACK = "rollback"
    ARCHITECTURE_SWITCH = "arch_switch"
    SCALE_UP = "scale_up"
    ALERT = "alert"


@dataclass(frozen=True)
class ModelMetrics:
    model_id: str
    timestamp: datetime
    accuracy: float
    latency_p99_ms: float
    cost_per_1k_usd: float
    drift_score: float

    @property
    def health_score(self) -> float:
        return (
            max(0.0, min(1.0, self.accuracy)) * 40.0
            + max(0.0, (1000.0 - self.latency_p99_ms) / 1000.0) * 30.0
            + (1.0 - max(0.0, min(1.0, self.drift_score))) * 30.0
        )

    @property
    def is_critical(self) -> bool:
        return self.health_score < 30.0 or self.drift_score > 0.35


class ModelVersion(BaseModel):
    version_id: str
    architecture: str
    weights_hash: str
    config_hash: str
    created_at: datetime
    health_at_creation: Optional[float] = None
    is_active: bool = False
    parent_version: Optional[str] = None
    deployment_stage: str = "canary"


class ActionRecord(BaseModel):
    timestamp: datetime
    action_type: ActionType
    reason: str
    parameters: dict
    success: bool = False
    error: Optional[str] = None


class MetricsSource(Protocol):
    async def fetch(self, model_id: str) -> ModelMetrics: ...
    async def aclose(self) -> None: ...


class CircuitBreaker:
    def __init__(self, failure_threshold: int = 3,
                 timeout: timedelta = timedelta(minutes=5)):
        self._threshold = failure_threshold
        self._timeout = timeout
        self._failures = 0
        self._last_failure: Optional[datetime] = None
        self._state = "CLOSED"

    async def call(self, fn: Callable, *args, **kwargs):
        now = datetime.now(timezone.utc)
        if self._state == "OPEN":
            if self._last_failure and now - self._last_failure > self._timeout:
                self._state = "HALF_OPEN"
            else:
                raise RuntimeError("circuit_open")
        try:
            result = await fn(*args, **kwargs)
            if self._state == "HALF_OPEN":
                self._state, self._failures = "CLOSED", 0
            return result
        except Exception:
            self._failures += 1
            self._last_failure = now
            if self._failures >= self._threshold:
                self._state = "OPEN"
            raise


class _LockedState:
    """Атомарная запись + advisory lock. На Android это важно: процесс
    может быть убит посреди write(), а два экземпляра — испортить файл."""

    def __init__(self, path: Path):
        self._path = path
        self._lock_path = path.with_suffix(".lock")
        self._fd: Optional[int] = None

    def __enter__(self):
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._fd = os.open(self._lock_path, os.O_CREAT | os.O_RDWR, 0o600)
        try:
            import fcntl
            fcntl.flock(self._fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except (ImportError, BlockingIOError) as exc:
            os.close(self._fd)
            self._fd = None
            raise RuntimeError(f"state_locked: {self._lock_path}") from exc
        return self

    def __exit__(self, *exc):
        if self._fd is not None:
            try:
                import fcntl
                fcntl.flock(self._fd, fcntl.LOCK_UN)
            finally:
                os.close(self._fd)
                self._fd = None

    def load(self) -> dict:
        if not self._path.exists():
            return {}
        return json.loads(self._path.read_text())

    def save(self, data: dict) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=self._path.parent, prefix=".state-")
        try:
            with os.fdopen(fd, "w") as f:
                json.dump(data, f, indent=2)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, self._path)  # атомарно на POSIX
        except BaseException:
            if os.path.exists(tmp):
                os.unlink(tmp)
            raise


class Kai9000Agent:
    def __init__(self, config: KAI9000Config, source: MetricsSource,
                 model_id: str = "prod-model"):
        self.config = config
        self.source = source
        self.model_id = model_id
        self.versions: list[ModelVersion] = []
        self.current_version: Optional[ModelVersion] = None
        self.action_history: list[ActionRecord] = []
        self._last_retrain: Optional[datetime] = None
        self._last_rollback: Optional[datetime] = None
        self._last_action: Optional[datetime] = None
        self._stop = asyncio.Event()
        self._action_lock = asyncio.Lock()
        self._canary_tasks: set[asyncio.Task] = set()
        self._metrics_breaker = CircuitBreaker(failure_threshold=3)
        self.queue: Optional["StreamActionQueue"] = None

    # ---------- public lifecycle ----------

    def attach_queue(self, q: "StreamActionQueue") -> None:
        self.queue = q

    async def run(self) -> None:
        self._load_state_locked()
        self._install_signal_handlers()
        log.info("agent_start state=%s dry_run=%s",
                 self.config.state_path, self.config.dry_run)
        try:
            while not self._stop.is_set():
                try:
                    await self._cycle()
                except Exception as exc:
                    log.exception("cycle_failed err=%s", exc)
                    try:
                        await asyncio.wait_for(self._stop.wait(), timeout=300)
                        break
                    except asyncio.TimeoutError:
                        continue
                try:
                    await asyncio.wait_for(self._stop.wait(), timeout=60)
                except asyncio.TimeoutError:
                    pass
        finally:
            for t in self._canary_tasks:
                t.cancel()
            if self._canary_tasks:
                await asyncio.gather(*self._canary_tasks,
                                     return_exceptions=True)
            self._persist_state_locked()
            if self.queue is not None:
                try:
                    await self.queue.aclose()
                except Exception as exc:
                    log.warning("queue_close_failed err=%s", exc)
            try:
                await self.source.aclose()
            except Exception as exc:
                log.warning("source_close_failed err=%s", exc)
            log.info("agent_stop")

    def stop(self) -> None:
        self._stop.set()

    def _install_signal_handlers(self) -> None:
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            try:
                loop.add_signal_handler(sig, self.stop)
            except NotImplementedError:
                pass

    # ---------- state ----------

    def _load_state_locked(self) -> None:
        try:
            with _LockedState(self.config.state_path) as s:
                raw = s.load()
        except RuntimeError as exc:
            log.error("state_lock_busy err=%s", exc)
            raise
        self.versions = [ModelVersion(**v) for v in raw.get("versions", [])]
        cv = raw.get("current_version")
        self.current_version = ModelVersion(**cv) if cv else None
        self.action_history = [ActionRecord(**a)
                               for a in raw.get("action_history", [])]
        for key, attr in (("last_retrain", "_last_retrain"),
                          ("last_rollback", "_last_rollback"),
                          ("last_action", "_last_action")):
            val = raw.get(key)
            setattr(self, attr, datetime.fromisoformat(val) if val else None)

    def _persist_state_locked(self) -> None:
        with _LockedState(self.config.state_path) as s:
            s.save({
                "versions": [v.model_dump(mode="json")
                             for v in self.versions],
                "current_version": (self.current_version.model_dump(mode="json")
                                    if self.current_version else None),
                "action_history": [a.model_dump(mode="json")
                                   for a in self.action_history[-500:]],
                "last_retrain": self._last_retrain.isoformat()
                    if self._last_retrain else None,
                "last_rollback": self._last_rollback.isoformat()
                    if self._last_rollback else None,
                "last_action": self._last_action.isoformat()
                    if self._last_action else None,
            })

    # ---------- loop ----------

    async def _cycle(self) -> None:
        metrics = await self._fetch_metrics_safe(self.model_id)
        age = (datetime.now(timezone.utc) - metrics.timestamp).total_seconds()
        if age > self.config.metrics_ttl_seconds:
            log.warning("stale_metrics age_s=%.0f ttl=%d",
                        age, self.config.metrics_ttl_seconds)
            return
        if self.current_version:
            self.current_version.health_at_creation = metrics.health_score
        drift = self._assess_drift(metrics.drift_score)
        log.info("health=%.1f drift=%.3f acc=%.3f lat=%.0fms",
                 metrics.health_score, metrics.drift_score,
                 metrics.accuracy, metrics.latency_p99_ms)
        action = self._decide(metrics, drift)
        if action is not None and self._can_take_action():
            await self._execute(action, metrics)

    async def _fetch_metrics_safe(self, model_id: str) -> ModelMetrics:
        return await self._metrics_breaker.call(self.source.fetch, model_id)

    async def _fetch_canary_metrics(self, version: ModelVersion) -> ModelMetrics:
        fetch_version = getattr(self.source, "fetch_for_version", None)
        if callable(fetch_version):
            return await fetch_version(self.model_id, version.version_id)
        log.warning("canary_metrics_fallback version=%s", version.version_id)
        return await self._fetch_metrics_safe(self.model_id)

    def _assess_drift(self, score: float) -> DriftSeverity:
        if score < 0.05:
            return DriftSeverity.NONE
        if score < 0.10:
            return DriftSeverity.LOW
        if score < 0.20:
            return DriftSeverity.MEDIUM
        if score < 0.35:
            return DriftSeverity.HIGH
        return DriftSeverity.CRITICAL

    def _decide(self, m: ModelMetrics,
                drift: DriftSeverity) -> Optional[dict]:
        if m.is_critical and self._can_rollback():
            t = self._last_stable_version()
            if t:
                return {"type": ActionType.ROLLBACK,
                        "reason": f"critical health={m.health_score:.1f}",
                        "target": t}
        if drift.value >= DriftSeverity.MEDIUM.value and self._can_retrain():
            return {"type": ActionType.RETRAIN,
                    "reason": f"drift={m.drift_score:.3f}",
                    "strategy": ("full" if drift is DriftSeverity.CRITICAL
                                 else "incremental")}
        cur_arch = (self.current_version.architecture
                    if self.current_version else "lightgbm")
        if m.cost_per_1k_usd > self.config.cost_limit_per_hour_usd * 0.8 / 1000:
            cheaper = self._cheaper_arch(cur_arch)
            if cheaper:
                return {"type": ActionType.ARCHITECTURE_SWITCH,
                        "reason": "cost_pressure", "target_arch": cheaper}
        if m.health_score < self.config.health_threshold and m.accuracy < 0.85:
            better = self._better_arch(cur_arch)
            if better:
                return {"type": ActionType.ARCHITECTURE_SWITCH,
                        "reason": "perf_degradation", "target_arch": better}
            return {"type": ActionType.ALERT,
                    "reason": (f"arch_exhausted cur={cur_arch} "
                               f"health={m.health_score:.1f} "
                               f"acc={m.accuracy:.3f}")}
        if m.latency_p99_ms > 500:
            return {"type": ActionType.SCALE_UP,
                    "reason": f"latency={m.latency_p99_ms:.0f}ms",
                    "factor": 1.5}
        return None

    # ---------- execution ----------

    async def _execute(self, action: dict, m: ModelMetrics) -> None:
        async with self._action_lock:
            kind: ActionType = action["type"]
            rec = ActionRecord(
                timestamp=datetime.now(timezone.utc),
                action_type=kind,
                reason=action["reason"],
                parameters={k: v for k, v in action.items()
                            if k not in ("type", "reason")},
            )
            try:
                log.info("action=%s reason=%s", kind.value, action["reason"])
                if self.config.dry_run:
                    log.info("dry_run: skipped")
                elif kind is ActionType.RETRAIN:
                    self._last_retrain = datetime.now(timezone.utc)
                    await self._retrain(action["strategy"])
                elif kind is ActionType.ROLLBACK:
                    self._last_rollback = datetime.now(timezone.utc)
                    self._rollback(action["target"])
                elif kind is ActionType.ARCHITECTURE_SWITCH:
                    await self._switch_arch(action["target_arch"])
                elif kind is ActionType.SCALE_UP:
                    log.info("scale_up factor=%s", action.get("factor", 2.0))
                elif kind is ActionType.ALERT:
                    await self._send_alert(f"agent_alert: {action['reason']}")
                rec.success = True
                self._last_action = datetime.now(timezone.utc)
            except Exception as exc:
                rec.error = str(exc)
                log.exception("action_failed kind=%s err=%s", kind.value, exc)
                await self._send_alert(f"action_failed {kind.value}: {exc}")
            finally:
                self.action_history.append(rec)
                if (self.queue is not None and rec.success
                        and not self.config.dry_run):
                    action_id = (f"{kind.value}-"
                                 f"{int(rec.timestamp.timestamp())}-"
                                 f"{_sha(rec.reason)}")
                    try:
                        ok = await self.queue.enqueue(
                            action_type=kind.value,
                            payload=rec.parameters,
                            action_id=action_id,
                        )
                        if not ok:
                            log.error("enqueue_failed id=%s kind=%s",
                                      action_id, kind.value)
                    except Exception as exc:
                        log.warning("enqueue_error id=%s err=%s",
                                    action_id, exc)
                self._persist_state_locked()

    async def _retrain(self, strategy: str) -> None:
        now = datetime.now(timezone.utc)
        new_version = ModelVersion(
            version_id=(f"v{len(self.versions) + 1}_"
                        f"{now.strftime('%Y%m%d_%H%M%S')}"),
            architecture=(self.current_version.architecture
                          if self.current_version else "lightgbm"),
            weights_hash=_sha(f"{now}{strategy}"),
            config_hash=_sha(self.config.model_dump_json()),
            created_at=now,
            parent_version=(self.current_version.version_id
                            if self.current_version else None),
        )
        self.versions.append(new_version)
        log.info("retrain_queued version=%s strategy=%s",
                 new_version.version_id, strategy)
        task = asyncio.create_task(self._monitor_canary(new_version))
        self._canary_tasks.add(task)
        task.add_done_callback(self._canary_tasks.discard)

    def _rollback(self, version_id: str) -> None:
        target = next((v for v in self.versions
                       if v.version_id == version_id), None)
        if target is None:
            raise ValueError(f"rollback_target_missing id={version_id}")
        if self.current_version:
            self.current_version.is_active = False
            self.current_version.deployment_stage = "archived"
        target.is_active = True
        target.deployment_stage = "stable"
        self.current_version = target

    async def _switch_arch(self, arch: str) -> None:
        if arch not in self.config.allowed_architectures:
            raise ValueError(f"arch_not_allowed target={arch}")
        log.info("arch_switch target=%s", arch)
        await self._retrain("full")

    # ---------- canary ----------

    async def _monitor_canary(self, version: ModelVersion) -> None:
        try:
            await asyncio.sleep(self.config.canary_duration_s)
            m = await self._fetch_canary_metrics(version)
            if m.health_score > self.config.health_threshold:
                self._promote_to_stable(version)
            else:
                self._rollback_canary(version)
        except asyncio.CancelledError:
            log.info("canary_cancelled version=%s", version.version_id)
            raise
        except Exception as exc:
            await self._handle_error(exc,
                                     f"canary_monitor {version.version_id}")
            self._rollback_canary(version)
        finally:
            self._persist_state_locked()

    def _promote_to_stable(self, version: ModelVersion) -> None:
        log.info("canary_promote version=%s", version.version_id)
        if self.current_version:
            self.current_version.is_active = False
            self.current_version.deployment_stage = "archived"
        version.is_active = True
        version.deployment_stage = "stable"
        self.current_version = version
        self._cleanup_old_versions()

    def _rollback_canary(self, version: ModelVersion) -> None:
        log.warning("canary_rollback version=%s", version.version_id)
        version.deployment_stage = "archived"
        version.is_active = False

    def _cleanup_old_versions(self) -> None:
        stable = [v for v in self.versions
                  if v.deployment_stage == "stable"]
        keep = set(v.version_id
                   for v in stable[-self.config.rollback_window:])
        keep.add(self.current_version.version_id
                 if self.current_version else "")
        self.versions = [v for v in self.versions
                         if v.version_id in keep
                         or v.deployment_stage == "canary"
                         or v.deployment_stage == "archived"][-200:]

    # ---------- alerts / errors ----------

    async def _send_alert(self, message: str) -> None:
        log.warning("alert: %s", message)
        if not self.config.alert_webhook:
            return
        try:
            import httpx
            async with httpx.AsyncClient(timeout=10.0) as client:
                r = await client.post(self.config.alert_webhook,
                                      json={"text": message})
                r.raise_for_status()
        except Exception as exc:
            log.warning("alert_failed err=%s", exc)

    async def _handle_error(self, exc: Exception, context: str) -> None:
        log.exception("error context=%s err=%s", context, exc)
        await self._send_alert(f"{context}: {exc}")

    # ---------- guards ----------

    def _can_retrain(self) -> bool:
        return (self._last_retrain is None
                or datetime.now(timezone.utc) - self._last_retrain
                   > self.config.max_retrain_interval)

    def _can_rollback(self) -> bool:
        if len(self.versions) < 2:
            return False
        if self._last_rollback is None:
            return True
        return (datetime.now(timezone.utc) - self._last_rollback
                > self.config.max_rollback_interval)

    def _can_take_action(self) -> bool:
        return (self._last_action is None
                or datetime.now(timezone.utc) - self._last_action
                   > self.config.min_action_interval)

    def _last_stable_version(self) -> Optional[str]:
        stable = [v for v in self.versions
                  if v.deployment_stage == "stable"
                  and v != self.current_version]
        return stable[-1].version_id if stable else None

    def _cheaper_arch(self, current: str) -> Optional[str]:
        cur = self.config.arch_cost_per_1k.get(current)
        if cur is None:
            return None
        cheaper = [(k, c) for k, c in self.config.arch_cost_per_1k.items()
                   if c < cur]
        return min(cheaper, key=lambda kv: kv[1])[0] if cheaper else None

    def _better_arch(self, current: str) -> Optional[str]:
        order = self.config.allowed_architectures
        try:
            i = order.index(current)
        except ValueError:
            return None
        if i + 1 < len(order):
            return order[i + 1]
        return None
4.3 kai9000/queue.py
python
# kai9000/queue.py — Redis Streams коннектор
#
# Писатель (телефон, Termux) и воркер (кластер) в одном модуле.
# on_shutdown-хук вызывается из run().finally ДО закрытия Redis-клиента
# воркера: сначала handler закрывает httpx kfp/k8s и redis-idempotency,
# потом закрывается Redis воркера.
from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime, timezone
from typing import Awaitable, Callable, Optional

import redis.asyncio as redis

log = logging.getLogger("kai9000.queue")

Handler = Callable[[dict], Awaitable[None]]
ShutdownHook = Callable[[], Awaitable[None]]

DEFAULT_STREAM = "kai9000:actions"
DEFAULT_GROUP = "kai9000-workers"

__all__ = [
    "StreamActionQueue",
    "StreamActionWorker",
    "DEFAULT_STREAM",
    "DEFAULT_GROUP",
]


class StreamActionQueue:
    """Fire-and-forget писатель. Агент пишет, воркер в кластере читает."""

    def __init__(self, url: str, stream: str = DEFAULT_STREAM,
                 maxlen: int = 10_000, retries: int = 3,
                 base_backoff_s: float = 0.5):
        self._url = url
        self._stream = stream
        self._maxlen = maxlen
        self._retries = max(1, retries)
        self._base_backoff = base_backoff_s
        self._client: Optional[redis.Redis] = None

    async def _connect(self) -> redis.Redis:
        if self._client is None:
            self._client = redis.from_url(
                self._url,
                decode_responses=True,
                socket_timeout=5.0,
                socket_connect_timeout=5.0,
                socket_keepalive=True,
                health_check_interval=30,
            )
        return self._client

    async def enqueue(self, action_type: str, payload: dict,
                      action_id: str) -> bool:
        client = await self._connect()
        body = {
            "action_id": action_id,
            "action_type": action_type,
            "payload": json.dumps(payload, separators=(",", ":")),
            "enqueued_at": datetime.now(timezone.utc).isoformat(),
        }
        last_exc: Optional[Exception] = None
        for attempt in range(self._retries):
            try:
                await client.xadd(self._stream, body,
                                  maxlen=self._maxlen, approximate=True)
                log.info("enqueue_ok id=%s type=%s", action_id, action_type)
                return True
            except Exception as exc:
                last_exc = exc
                if attempt + 1 < self._retries:
                    await asyncio.sleep(self._base_backoff * (2 ** attempt))
        log.error("enqueue_failed id=%s err=%s", action_id, last_exc)
        return False

    async def aclose(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None


class StreamActionWorker:
    """Consumer group. Читает stream, вызывает handler, делает XACK."""

    def __init__(self, url: str, stream: str = DEFAULT_STREAM,
                 group: str = DEFAULT_GROUP,
                 consumer: str = "worker-1",
                 min_idle_ms: int = 60_000,
                 block_ms: int = 5_000,
                 batch: int = 10,
                 on_shutdown: Optional[ShutdownHook] = None):
        self._url = url
        self._stream = stream
        self._group = group
        self._consumer = consumer
        self._min_idle_ms = min_idle_ms
        self._block_ms = block_ms
        self._batch = max(1, batch)
        self._on_shutdown = on_shutdown
        self._client: Optional[redis.Redis] = None
        self._stop = asyncio.Event()

    async def _connect(self) -> redis.Redis:
        if self._client is None:
            self._client = redis.from_url(
                self._url,
                decode_responses=True,
                socket_timeout=None,
                socket_connect_timeout=5.0,
                socket_keepalive=True,
                health_check_interval=30,
            )
        return self._client

    async def _ensure_group(self) -> None:
        client = await self._connect()
        try:
            await client.xgroup_create(
                self._stream, self._group, id="0", mkstream=True)
            log.info("consumer_group_created group=%s stream=%s",
                     self._group, self._stream)
        except redis.ResponseError as exc:
            if "BUSYGROUP" not in str(exc):
                raise

    def stop(self) -> None:
        self._stop.set()

    async def run(self, handler: Handler) -> None:
        await self._ensure_group()
        client = await self._connect()
        log.info("worker_start group=%s consumer=%s",
                 self._group, self._consumer)
        try:
            while not self._stop.is_set():
                try:
                    await self._reclaim_and_process(client, handler)
                    resp = await client.xreadgroup(
                        self._group, self._consumer,
                        {self._stream: ">"},
                        count=self._batch, block=self._block_ms,
                    )
                    if not resp:
                        continue
                    for _stream, messages in resp:
                        for msg_id, fields in messages:
                            await self._handle(client, msg_id, fields, handler)
                except asyncio.CancelledError:
                    raise
                except Exception as exc:
                    log.exception("worker_loop_error err=%s", exc)
                    await asyncio.sleep(2)
        finally:
            if self._on_shutdown is not None:
                try:
                    await self._on_shutdown()
                except Exception as exc:
                    log.exception("on_shutdown_error err=%s", exc)
            await self.aclose()
            log.info("worker_stop consumer=%s", self._consumer)

    async def _reclaim_and_process(self, client, handler: Handler) -> None:
        try:
            claimed = await client.xautoclaim(
                self._stream, self._group, self._consumer,
                min_idle_time=self._min_idle_ms,
                start_id="0-0",
                count=self._batch,
            )
        except Exception as exc:
            log.warning("xautoclaim_error err=%s", exc)
            return
        entries = claimed[1] if len(claimed) >= 2 else []
        for msg_id, fields in entries:
            if fields:
                await self._handle(client, msg_id, fields, handler)

    async def _handle(self, client, msg_id: str, fields: dict,
                      handler: Handler) -> None:
        try:
            payload = json.loads(fields["payload"])
            await handler({
                "action_id": fields["action_id"],
                "action_type": fields["action_type"],
                "payload": payload,
                "enqueued_at": fields["enqueued_at"],
            })
            await client.xack(self._stream, self._group, msg_id)
            log.info("action_done msg_id=%s id=%s",
                     msg_id, fields.get("action_id"))
        except Exception as exc:
            log.exception("handler_failed msg_id=%s id=%s err=%s",
                          msg_id, fields.get("action_id"), exc)

    async def aclose(self) -> None:
        if self._client is not None:
            await self._client.aclose()
            self._client = None
4.4 kai9000/termux_entry.py
python
# kai9000/termux_entry.py — Termux-обёртка KAI9000
from __future__ import annotations

import asyncio
import json
import logging
import logging.handlers
import os
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import httpx

from kai9000.core import (
    KAI9000Config,
    Kai9000Agent,
    MetricsSource,
    ModelMetrics,
)

log = logging.getLogger("kai9000.termux")

HOME = Path.home()
STATE_DIR = HOME / ".kai9000"
STATE_DIR.mkdir(parents=True, exist_ok=True)
LOG_PATH = STATE_DIR / "agent.log"
METRICS_FILE = STATE_DIR / "metrics.json"

ALERT_WINDOW_S = 300.0
WATCHER_TICK_S = 60.0


class EnqueueAlertThrottle:
    def __init__(self, window_s: float = ALERT_WINDOW_S):
        self._window = window_s
        self._last_alert: float = 0.0
        self._fails = 0

    def record_failure(self) -> None:
        self._fails += 1

    def record_success(self) -> None:
        self._fails = 0
        self._last_alert = 0.0

    def should_alert(self) -> bool:
        if self._fails <= 0:
            return False
        now = time.monotonic()
        if now - self._last_alert >= self._window:
            self._last_alert = now
            return True
        return False

    @property
    def fails(self) -> int:
        return self._fails


class FileMetricsSource:
    def __init__(self, path: Path):
        self._path = path

    async def fetch(self, model_id: str) -> ModelMetrics:
        st = self._path.stat()
        raw = json.loads(self._path.read_text())
        return ModelMetrics(
            model_id=raw.get("model_id", model_id),
            timestamp=datetime.fromtimestamp(st.st_mtime, timezone.utc),
            accuracy=float(raw["accuracy"]),
            latency_p99_ms=float(raw["latency_p99_ms"]),
            cost_per_1k_usd=float(raw["cost_per_1k_usd"]),
            drift_score=float(raw["drift_score"]),
        )

    async def aclose(self) -> None:
        pass


class HttpMetricsSource:
    def __init__(self, base_url: str, retries: int = 3):
        self._base = base_url.rstrip("/")
        self._client = httpx.AsyncClient(timeout=10.0)
        self._retries = max(1, retries)
        self._last: Optional[ModelMetrics] = None

    async def _query(self, expr: str) -> float:
        last_exc: Optional[Exception] = None
        for attempt in range(self._retries):
            try:
                r = await self._client.get(
                    f"{self._base}/api/v1/query", params={"query": expr})
                r.raise_for_status()
                result = r.json()["data"]["result"]
                if len(result) != 1:
                    raise RuntimeError(
                        f"expected 1 series, got {len(result)} for {expr}")
                return float(result[0]["value"][1])
            except Exception as exc:
                last_exc = exc
                if attempt + 1 < self._retries:
                    await asyncio.sleep(2 ** attempt)
        assert last_exc is not None
        raise last_exc

    async def fetch(self, model_id: str) -> ModelMetrics:
        try:
            acc = await self._query(f'model_accuracy{{model="{model_id}"}}')
            lat = await self._query(
                f'model_latency_p99_ms{{model="{model_id}"}}')
            cost = await self._query(
                f'model_cost_per_1k_usd{{model="{model_id}"}}')
            drift = await self._query(
                f'model_drift_score{{model="{model_id}"}}')
            m = ModelMetrics(model_id, datetime.now(timezone.utc),
                             acc, lat, cost, drift)
            self._last = m
            return m
        except Exception as exc:
            if self._last is not None:
                log.warning("metrics_fallback err=%s", exc)
                return self._last
            raise

    async def aclose(self) -> None:
        try:
            await self._client.aclose()
        except Exception as exc:
            log.warning("metrics_client_close_failed err=%s", exc)


def _setup_logging() -> None:
    handler = logging.handlers.RotatingFileHandler(
        LOG_PATH, maxBytes=5 * 1024 * 1024, backupCount=3)
    handler.setFormatter(logging.Formatter(
        "%(asctime)s %(levelname)s %(name)s %(message)s"))
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    root.addHandler(handler)
    root.addHandler(logging.StreamHandler())


def _wrap_enqueue(queue, throttle: EnqueueAlertThrottle) -> None:
    orig = queue.enqueue

    async def _wrapped(action_type: str, payload: dict,
                       action_id: str) -> bool:
        ok = await orig(action_type=action_type, payload=payload,
                        action_id=action_id)
        if ok:
            throttle.record_success()
        else:
            throttle.record_failure()
            log.error("enqueue_failed id=%s type=%s", action_id, action_type)
        return ok

    queue.enqueue = _wrapped  # type: ignore[assignment]


async def _alert_watcher(agent: Kai9000Agent,
                         throttle: EnqueueAlertThrottle,
                         stop: asyncio.Event) -> None:
    while not stop.is_set():
        try:
            await asyncio.wait_for(stop.wait(), timeout=WATCHER_TICK_S)
            break
        except asyncio.TimeoutError:
            pass
        if not throttle.should_alert():
            continue
        msg = (f"KAI9000: {throttle.fails} enqueue failures in last "
               f"window — Redis tunnel down?")
        log.error(msg)
        send = getattr(agent, "_send_alert", None)
        if callable(send):
            try:
                await send(msg)
            except Exception as exc:
                log.warning("alert_send_failed err=%s", exc)


async def main() -> None:
    _setup_logging()
    cfg = KAI9000Config(state_path=STATE_DIR / "state.json")
    cfg.state_path.parent.mkdir(parents=True, exist_ok=True)

    if METRICS_FILE.exists():
        source: MetricsSource = FileMetricsSource(METRICS_FILE)
        log.info("metrics_source=file path=%s", METRICS_FILE)
    else:
        source = HttpMetricsSource("http://127.0.0.1:9090")
        log.info("metrics_source=http (ssh tunnel expected on :9090)")

    agent = Kai9000Agent(cfg, source, model_id="prod-model")

    throttle = EnqueueAlertThrottle(window_s=ALERT_WINDOW_S)
    redis_url = os.environ.get("KAI9000_REDIS_URL")
    if redis_url:
        from kai9000.queue import StreamActionQueue
        queue = StreamActionQueue(redis_url)
        attach = getattr(agent, "attach_queue", None)
        if callable(attach):
            attach(queue)
        else:
            agent.queue = queue  # type: ignore[attr-defined]
        _wrap_enqueue(queue, throttle)
        log.info("action_queue=redis stream=kai9000:actions")
    else:
        log.warning("KAI9000_REDIS_URL not set — actions are audit-only")

    stop = asyncio.Event()
    watcher = asyncio.create_task(
        _alert_watcher(agent, throttle, stop),
        name="enqueue-alert-watcher",
    )
    try:
        await agent.run()
    finally:
        stop.set()
        watcher.cancel()
        await asyncio.gather(watcher, return_exceptions=True)
        for closer in (getattr(source, "aclose", None),
                       getattr(getattr(agent, "queue", None),
                               "aclose", None)):
            if callable(closer):
                try:
                    await closer()
                except Exception as exc:
                    log.warning("close_failed err=%s", exc)


if __name__ == "__main__":
    asyncio.run(main())
4.5 cluster/handler.py
python
# cluster/handler.py — финал
from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass
from typing import Optional

import httpx
import redis.asyncio as redis

log = logging.getLogger("kai9000.handler")

APPLIED_KEY = "kai9000:applied"
APPLIED_TTL_S = 7 * 24 * 3600

SA_DIR = "/var/run/secrets/kubernetes.io/serviceaccount"
KSERVE_PATH = ("/apis/serving.kserve.io/v1beta1"
               "/namespaces/{ns}/inferenceservices/{name}")


@dataclass
class HandlerDeps:
    redis_url: str
    kubeflow_url: str
    kubeflow_token: str
    kube_api_url: str = "https://kubernetes.default.svc"
    kube_token: Optional[str] = None
    sa_token_path: str = f"{SA_DIR}/token"
    kube_ca_path: str = f"{SA_DIR}/ca.crt"
    kserve_namespace: str = "ml-system"
    kserve_isvc: str = "prod-model"
    scale_target_kind: str = "Deployment"
    scale_target_name: str = "prod-model"
    alert_webhook: Optional[str] = None
    http_timeout_s: float = 30.0


class SAToken:
    def __init__(self, path: str, min_interval_s: float = 30.0):
        self._path = path
        self._min_interval = min_interval_s
        self._value: Optional[str] = None
        self._read_at: float = 0.0

    def get(self, force: bool = False) -> str:
        now = time.monotonic()
        if (force or self._value is None
                or now - self._read_at > self._min_interval):
            with open(self._path) as f:
                self._value = f.read().strip()
            self._read_at = now
        return self._value


class Idempotency:
    def __init__(self, client: redis.Redis):
        self._r = client

    async def claim(self, action_id: str) -> str:
        key = f"{APPLIED_KEY}:{action_id}"
        if await self._r.set(key, "pending", nx=True, ex=APPLIED_TTL_S):
            return "new"
        state = await self._r.get(key)
        return state or "new"

    async def mark_done(self, action_id: str) -> None:
        await self._r.set(f"{APPLIED_KEY}:{action_id}", "done",
                          ex=APPLIED_TTL_S)

    async def release(self, action_id: str) -> None:
        await self._r.delete(f"{APPLIED_KEY}:{action_id}")


async def _k8s_patch(client: httpx.AsyncClient, deps: HandlerDeps,
                     sa: SAToken, path: str, patch: dict,
                     action_id: str) -> dict:
    url = f"{deps.kube_api_url}{path}"
    for attempt in (0, 1):
        token = deps.kube_token or sa.get(force=attempt == 1)
        r = await client.patch(url, json=patch, headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/strategic-merge-patch+json",
        })
        if r.status_code == 401 and attempt == 0 and not deps.kube_token:
            log.warning("k8s_401_token_refresh id=%s", action_id)
            continue
        r.raise_for_status()
        return r.json()
    raise RuntimeError("k8s_patch_unreachable")


async def _kubeflow_run(client: httpx.AsyncClient, deps: HandlerDeps,
                        action_id: str, params: dict) -> dict:
    name = f"kai9000-{action_id}"
    headers = {"Authorization": f"Bearer {deps.kubeflow_token}"}
    r = await client.get(f"{deps.kubeflow_url}/apis/v2beta1/runs",
                         params={"filter": f'display_name = "{name}"'},
                         headers=headers)
    r.raise_for_status()
    runs = r.json().get("runs") or []
    if runs:
        log.info("kfp_run_exists id=%s run_id=%s", action_id,
                 runs[0].get("run_id"))
        return runs[0]
    body = {
        "display_name": name,
        "pipeline_version_id": params["pipeline_version_id"],
        "runtime_config": {"parameters": params.get("parameters", {})},
        "service_account": params.get("service_account", "default-editor"),
    }
    r = await client.post(f"{deps.kubeflow_url}/apis/v2beta1/runs",
                          json=body, headers=headers)
    r.raise_for_status()
    return r.json()


async def _kserve_patch_arch(client: httpx.AsyncClient, deps: HandlerDeps,
                             sa: SAToken, action_id: str,
                             target_arch: str) -> dict:
    patch = {
        "metadata": {"annotations": {
            "kai9000/action-id": action_id,
            "kai9000/arch": target_arch,
        }},
        "spec": {"predictor": {"model": {
            "args": ["--arch", target_arch],
        }}},
    }
    return await _k8s_patch(
        client, deps, sa,
        KSERVE_PATH.format(ns=deps.kserve_namespace, name=deps.kserve_isvc),
        patch, action_id)


async def _kserve_rollback(client: httpx.AsyncClient, deps: HandlerDeps,
                           sa: SAToken, action_id: str,
                           target_version: str) -> dict:
    patch = {
        "metadata": {"annotations": {
            "kai9000/action-id": action_id,
            "kai9000/rollback-to": target_version,
        }},
        "spec": {"predictor": {"model": {
            "env": [{"name": "MODEL_VERSION", "value": target_version}],
        }}},
    }
    return await _k8s_patch(
        client, deps, sa,
        KSERVE_PATH.format(ns=deps.kserve_namespace, name=deps.kserve_isvc),
        patch, action_id)


async def _k8s_scale(client: httpx.AsyncClient, deps: HandlerDeps,
                     sa: SAToken, action_id: str, factor: float) -> dict:
    kind = deps.scale_target_kind
    if kind == "InferenceService":
        path = (
            f"/apis/serving.kserve.io/v1beta1"
            f"/namespaces/{deps.kserve_namespace}"
            f"/inferenceservices/{deps.scale_target_name}/scale"
        )
    elif kind == "Deployment":
        path = (
            f"/apis/apps/v1/namespaces/{deps.kserve_namespace}"
            f"/deployments/{deps.scale_target_name}/scale"
        )
    else:
        raise ValueError(f"unsupported scale_target_kind={kind}")

    url = f"{deps.kube_api_url}{path}"
    for attempt in (0, 1):
        token = deps.kube_token or sa.get(force=attempt == 1)
        headers = {"Authorization": f"Bearer {token}"}
        r = await client.get(url, headers=headers)
        if r.status_code == 401 and attempt == 0 and not deps.kube_token:
            log.warning("k8s_401_token_refresh_scale id=%s", action_id)
            continue
        if r.status_code == 403:
            raise PermissionError(
                f"rbac_missing_scale kind={kind} "
                f"need verbs get,patch on {kind.lower()}s/scale")
        r.raise_for_status()
        current = int(r.json()["spec"]["replicas"])
        target = max(1, min(64, int(current * factor + 0.999)))
        r = await client.patch(
            url,
            json={"spec": {"replicas": target}},
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/merge-patch+json",
            },
        )
        if r.status_code == 403:
            raise PermissionError(
                f"rbac_missing_scale_patch kind={kind}")
        r.raise_for_status()
        log.info("scale_applied id=%s kind=%s from=%d to=%d",
                 action_id, kind, current, target)
        return r.json()
    raise RuntimeError("k8s_scale_unreachable")


def make_handler(deps: HandlerDeps):
    state: dict = {"kfp": None, "k8s": None, "redis": None, "idem": None,
                   "sa": None}

    async def _ensure():
        if state["kfp"] is None:
            state["kfp"] = httpx.AsyncClient(timeout=deps.http_timeout_s)
        if state["k8s"] is None:
            state["k8s"] = httpx.AsyncClient(
                timeout=deps.http_timeout_s,
                verify=deps.kube_ca_path,
            )
            state["sa"] = SAToken(deps.sa_token_path)
        if state["idem"] is None:
            state["redis"] = redis.from_url(deps.redis_url,
                                            decode_responses=True)
            state["idem"] = Idempotency(state["redis"])
        return state

    async def _send_cluster_alert(message: str) -> None:
        log.error("cluster_alert msg=%s", message)
        if not deps.alert_webhook:
            return
        try:
            async with httpx.AsyncClient(timeout=10.0) as c:
                r = await c.post(deps.alert_webhook, json={"text": message})
                r.raise_for_status()
        except Exception as exc:
            log.warning("cluster_alert_send_failed err=%s", exc)

    async def handler(msg: dict) -> None:
        action_id = msg["action_id"]
        kind = msg["action_type"]
        payload = msg["payload"]
        st = await _ensure()
        idem: Idempotency = st["idem"]

        if kind == "alert":
            await _send_cluster_alert(payload.get("reason", "no reason"))
            return

        status = await idem.claim(action_id)
        if status == "done":
            log.info("skip_already_done id=%s", action_id)
            return
        if status == "pending":
            log.warning("still_pending id=%s backoff=5s", action_id)
            await asyncio.sleep(5)
            if await idem.claim(action_id) == "done":
                return

        try:
            if kind == "retrain":
                res = await _kubeflow_run(st["kfp"], deps, action_id, payload)
            elif kind == "arch_switch":
                res = await _kserve_patch_arch(
                    st["k8s"], deps, st["sa"], action_id,
                    payload["target_arch"])
            elif kind == "rollback":
                res = await _kserve_rollback(
                    st["k8s"], deps, st["sa"], action_id,
                    payload["target"])
            elif kind == "scale_up":
                res = await _k8s_scale(
                    st["k8s"], deps, st["sa"], action_id,
                    factor=float(payload.get("factor", 1.5)))
            else:
                log.warning("unknown_action id=%s kind=%s", action_id, kind)
                res = {"skipped": True}
            log.info("applied id=%s kind=%s res=%s", action_id, kind, res)
            await idem.mark_done(action_id)
        except Exception as exc:
            log.exception("apply_failed id=%s kind=%s err=%s",
                          action_id, kind, exc)
            await idem.release(action_id)
            raise

    async def aclose() -> None:
        for k in ("kfp", "k8s"):
            if state[k] is not None:
                try:
                    await state[k].aclose()
                except Exception as exc:
                    log.warning("close_%s_failed err=%s", k, exc)
        if state["redis"] is not None:
            try:
                await state["redis"].aclose()
            except Exception as exc:
                log.warning("close_redis_failed err=%s", exc)

    handler.aclose = aclose  # type: ignore[attr-defined]
    return handler
4.6 cluster/worker_entry.py
python
# cluster/worker_entry.py — финал
from __future__ import annotations

import asyncio
import logging
import os
import signal

from kai9000.queue import StreamActionWorker
from cluster.handler import HandlerDeps, make_handler

log = logging.getLogger("kai9000.cluster_worker")


async def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )

    deps = HandlerDeps(
        redis_url=os.environ.get("KAI9000_REDIS_URL",
                                 "redis://redis:6379/0"),
        kubeflow_url=os.environ.get("KUBEFLOW_URL",
                                    "http://ml-pipeline:8888"),
        kubeflow_token=os.environ.get("KUBEFLOW_TOKEN", ""),
        kube_api_url=os.environ.get("KUBE_API_URL",
                                    "https://kubernetes.default.svc"),
        kserve_namespace=os.environ.get("KSERVE_NAMESPACE", "ml-system"),
        kserve_isvc=os.environ.get("KSERVE_ISVC", "prod-model"),
        scale_target_kind=os.environ.get("SCALE_TARGET_KIND", "Deployment"),
        scale_target_name=os.environ.get("SCALE_TARGET_NAME", "prod-model"),
        alert_webhook=os.environ.get("KAI9000_CLUSTER_ALERT_WEBHOOK"),
    )

    handler = make_handler(deps)
    aclose = getattr(handler, "aclose", None)
    if not callable(aclose):
        raise RuntimeError("handler must expose aclose()")

    worker = StreamActionWorker(
        url=deps.redis_url,
        consumer=os.environ.get("KAI9000_CONSUMER", "worker-1"),
        on_shutdown=aclose,
    )

    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, worker.stop)
        except NotImplementedError:
            pass

    await worker.run(handler)
    log.info("cluster_worker_stopped")


if __name__ == "__main__":
    asyncio.run(main())
4.7 smoke.py
python
# smoke.py — запускать из корня проекта, где лежит kai9000/
import asyncio
import json
import pathlib
import tempfile
from datetime import datetime, timedelta, timezone

from kai9000.core import KAI9000Config, Kai9000Agent, ModelMetrics


class FakeSource:
    def __init__(self):
        self.calls = 0
        self.closed = False

    async def fetch(self, model_id):
        self.calls += 1
        return ModelMetrics(
            model_id=model_id,
            timestamp=datetime.now(timezone.utc),
            accuracy=0.40,
            latency_p99_ms=900.0,
            cost_per_1k_usd=0.20,
            drift_score=0.40,
        )

    async def aclose(self):
        self.closed = True


class FakeQueue:
    def __init__(self, ok: bool = True):
        self.ok = ok
        self.enqueued = []
        self.closed = False

    async def enqueue(self, action_type, payload, action_id):
        self.enqueued.append((action_type, action_id, payload))
        return self.ok

    async def aclose(self):
        self.closed = True


async def main():
    with tempfile.TemporaryDirectory() as tmp:
        d = pathlib.Path(tmp)
        cfg = KAI9000Config(
            state_path=d / "state.json",
            dry_run=False,
            min_action_interval=timedelta(seconds=0),
            canary_duration_s=1,
        )
        source = FakeSource()
        queue = FakeQueue(ok=True)
        agent = Kai9000Agent(cfg, source, model_id="prod-model")
        agent.attach_queue(queue)

        task = asyncio.create_task(agent.run())
        await asyncio.sleep(2)
        agent.stop()
        await asyncio.wait_for(task, timeout=5)

        assert source.calls >= 1, "fetch не вызывался"
        assert source.closed, "source.aclose() не вызван"
        assert queue.closed, "queue.aclose() не вызван"
        assert queue.enqueued, "queue.enqueue() не вызван — _execute не дошёл"
        assert (d / "state.json").exists(), "state.json не создан"
        state = json.loads((d / "state.json").read_text())
        assert state["action_history"], "action_history пуст"
        kinds = [a["action_type"] for a in state["action_history"]]
        assert kinds[0] == "retrain", f"ожидался retrain, получено {kinds}"
        assert queue.enqueued[0][0] == "retrain"
        print("actions:", kinds)
        print("enqueued:", [q[0] for q in queue.enqueued])
        print("SMOKE OK")


if __name__ == "__main__":
    asyncio.run(main())
5. Запуск
5.1 Termux (пульт)
bash
pkg update -y && pkg upgrade -y
pkg install -y python python-pip python-numpy git openssh tmux termux-api \
    clang make libffi openssl rust binutils

pip install --upgrade pip
pip install httpx pydantic pydantic-settings redis

mkdir -p ~/.kai9000
cat > ~/.kai9000/metrics.json <<'EOF'
{"model_id":"prod-model","accuracy":0.91,"latency_p99_ms":48,
 "cost_per_1k_usd":0.07,"drift_score":0.12}
EOF

termux-wake-lock

# SSH-туннель к Redis и Prometheus (отдельная tmux-сессия)
ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
    -L 127.0.0.1:6379:redis:6379 \
    -L 127.0.0.1:9090:prometheus:9090 user@cluster-host &

export KAI9000_REDIS_URL="redis://127.0.0.1:6379/0"
tmux new -s kai9000 -c ~/kai9000 'python -m kai9000.termux_entry'
5.2 APK (Kivy-пульт)
Требуется Linux-хост с JDK 17, Android SDK/NDK (buildozer качает сам),
~15 GB диска. Сборка на телефоне невозможна.

bash
pip install buildozer cython
buildozer -v android debug
# → bin/kai9000-1.0.0-arm64-v8a-debug.apk
Установка: adb install bin/kai9000-*.apk. Внутри: старт/стоп, dry_run,
URL Redis (без SSH — APK без root не поднимает ssh -L в фоне; Redis
должен быть доступен по rediss://), ручные действия, лог.

5.3 Кластер (воркер)
bash
kubectl apply -f k8s-cluster-worker.yaml
kubectl -n ml-system logs -f deploy/kai9000-worker
5.4 Смоук
bash
python -m py_compile kai9000/core.py && python -c "import kai9000.core; print('OK')"
python smoke.py
# → actions: ['retrain']
#   enqueued: ['retrain']
#   SMOKE OK
6. Кластерные проверки
cluster-checks.sh — четыре шага, шаг 1 блокирует остальные.

bash
chmod +x cluster-checks.sh
NS=ml-system DEPLOY=kai9000-worker ./cluster-checks.sh
./cluster-checks.sh --only 2
APPLY_POLICY=1 ./cluster-checks.sh
Шаг	Проверка	Ожидается	При провале
1	pip show pydantic-settings	Version: 2.x	Пересобрать образ с >=2,<3 или откатить config.py на v1 API
2	curl .../apis/v2beta1/runs	200	401 — Secret/oidc; 403 — RBAC KFP; 000 — DNS
3	kubectl get hpa	ветка A/B/C	B → решение B1 (delete hpa) или B2 (переписать _k8s_scale на PATCH spec.minReplicas HPA)
4	NetworkPolicy + связность	PONG + 200 + 200	Заменить метки на реальные; CIDR — по kubectl get svc kubernetes
7. Что осталось внешним
Решения, не код:

Ветка HPA = B: удалить HPA (B1) или переписать _k8s_scale на
PATCH /apis/autoscaling/v2/.../horizontalpodautoscalers/<name> с
spec.minReplicas (B2). B2 — отдельная правка handler.py,
RBAC меняется с */scale на horizontalpodautoscalers.

KUBEFLOW_TOKEN: если KFP за oidc-authservice — env-токен не
подойдёт, нужен SA JWT namespace Kubeflow или port-forward мимо proxy.

Инфраструктурные проверки:

pydantic-settings >= 2 в образе воркера.

NetworkPolicy: Service CIDR API-сервера, метки подов — реальные.

Известные замечания, не разрывы:

Termux при detach tmux шлёт SIGHUP — _install_signal_handlers
ловит только SIGINT/SIGTERM. Либо tmux set-option -g destroy-unattached off,
либо +signal.SIGHUP в core.py.

Пул LLM (как у Kai со скриншота) в KAI9000 не участвует в _decide —
решения детерминированные. Если нужен чат-помощник оператора в APK —
это отдельный llm_pool.py, не в контуре принятия решений.

Alert-каналы телефона и кластера дублируются, если URL один и тот же.
Два независимых канала на разные классы инцидентов; для маршрутизации —
разные URL.

text

Один факт для исполнителя, который в MD не помещён в код, но помещён в разделы 1 и 7: `Kai9000Agent` детерминированный, пул LLM (если он есть в другом приложении на телефоне) к решениям отношения не имеет. Если исполнитель попытается встроить LLM в `_decide` — он сломает воспроизводимость canary-gate и rollback. Это архитектурное решение, а не вкусовое.

Код в MD полный, файлы целиком, без обрывов. Каждый файл можно скопировать в свой путь и получить сходящуюся сборку.
