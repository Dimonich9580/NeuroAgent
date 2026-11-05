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


def _acquire_fd_lock(fd: int) -> None:
    """Эксклюзивный non-blocking advisory lock на файловом дескрипторе.

    fcntl на POSIX (Termux, кластер) — основной путь. msvcrt на Windows
    нужен только чтобы смоук и юнит-прогон шли на dev-машине.
    """
    os.lseek(fd, 0, os.SEEK_SET)
    try:
        import fcntl
    except ImportError:
        import msvcrt
        msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
    else:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)


def _release_fd_lock(fd: int) -> None:
    os.lseek(fd, 0, os.SEEK_SET)
    try:
        import fcntl
    except ImportError:
        import msvcrt
        try:
            msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
        except OSError:
            pass
    else:
        fcntl.flock(fd, fcntl.LOCK_UN)


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
            _acquire_fd_lock(self._fd)
        except (ImportError, OSError) as exc:
            os.close(self._fd)
            self._fd = None
            raise RuntimeError(f"state_locked: {self._lock_path}") from exc
        return self

    def __exit__(self, *exc):
        if self._fd is not None:
            try:
                _release_fd_lock(self._fd)
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
