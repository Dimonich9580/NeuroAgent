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
