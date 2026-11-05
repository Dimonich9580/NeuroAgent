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
