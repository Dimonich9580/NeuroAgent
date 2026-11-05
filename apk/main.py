"""KAI9000 APK-пульт (Kivy).

Один экран: строка Redis, переключатель dry_run, старт/стоп агента,
ручные действия и лог. Агент крутится в отдельном потоке со своим
asyncio-loop; UI остаётся отзывчивым.

Ручное действие не проходит через _decide — оператор сам выбирает тип,
payload собирается здесь и уходит в тот же Redis Stream.
"""

from __future__ import annotations

import asyncio
import json
import logging
import threading
from pathlib import Path
from typing import Optional

from kivy.app import App
from kivy.clock import Clock
from kivy.properties import BooleanProperty, StringProperty
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.scrollview import ScrollView
from kivy.uix.switch import Switch
from kivy.uix.textinput import TextInput

CONFIG_PATH = Path.home() / ".kai9000" / "apk.json"
LOG_LIMIT = 400


# ---------------------------------------------------------------- log bridge

class _UiLogHandler(logging.Handler):
    """Копит строки в кольцевом буфере; UI забирает их по тику."""

    def __init__(self, sink: "_LogSink") -> None:
        super().__init__()
        self._sink = sink
        self.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s",
                                            datefmt="%H:%M:%S"))

    def emit(self, record: logging.LogRecord) -> None:
        try:
            self._sink.push(self.format(record))
        except Exception:
            pass


class _LogSink:
    def __init__(self) -> None:
        self._lines: list[str] = []
        self._lock = threading.Lock()

    def push(self, line: str) -> None:
        with self._lock:
            self._lines.append(line)
            if len(self._lines) > LOG_LIMIT:
                del self._lines[:-LOG_LIMIT]

    def drain(self) -> list[str]:
        with self._lock:
            out, self._lines = self._lines, []
        return out


# ---------------------------------------------------------------- settings

def load_settings() -> dict:
    cfg = {"redis_url": "", "dry_run": True}
    try:
        if CONFIG_PATH.exists():
            cfg.update(json.loads(CONFIG_PATH.read_text(encoding="utf-8")))
    except (OSError, json.JSONDecodeError):
        pass
    return cfg


def save_settings(cfg: dict) -> None:
    CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    CONFIG_PATH.write_text(json.dumps(cfg, indent=2), encoding="utf-8")


# ---------------------------------------------------------------- agent host

class AgentHost:
    """Поток с asyncio-loop. Один экземпляр агента на устройство."""

    def __init__(self, sink: _LogSink) -> None:
        self._sink = sink
        self._thread: Optional[threading.Thread] = None
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._agent = None
        self._source = None
        self._ready = threading.Event()

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self, redis_url: str, dry_run: bool) -> None:
        if self.running:
            return
        self._ready.clear()
        self._thread = threading.Thread(
            target=self._run, args=(redis_url, dry_run), daemon=True,
            name="kai9000-agent",
        )
        self._thread.start()
        self._ready.wait(timeout=5.0)

    def stop(self) -> None:
        loop, agent = self._loop, self._agent
        if loop is not None and agent is not None:
            loop.call_soon_threadsafe(agent.stop)
        if self._thread is not None:
            self._thread.join(timeout=10.0)
        self._thread = None
        self._loop = None
        self._agent = None

    # --- worker thread ---

    def _run(self, redis_url: str, dry_run: bool) -> None:
        from kai9000.core import KAI9000Config, Kai9000Agent
        from kai9000.termux_entry import (
            FileMetricsSource,
            HttpMetricsSource,
            METRICS_FILE,
            _wrap_enqueue,
            EnqueueAlertThrottle,
        )

        loop = asyncio.new_event_loop()
        self._loop = loop
        asyncio.set_event_loop(loop)
        try:
            cfg = KAI9000Config(dry_run=dry_run,
                                state_path=Path.home() / ".kai9000" / "state.json")
            source = (FileMetricsSource(METRICS_FILE) if METRICS_FILE.exists()
                      else HttpMetricsSource("http://127.0.0.1:9090"))
            agent = Kai9000Agent(cfg, source, model_id="prod-model")

            if redis_url:
                from kai9000.queue import StreamActionQueue
                queue = StreamActionQueue(redis_url)
                agent.attach_queue(queue)
                _wrap_enqueue(queue, EnqueueAlertThrottle())

            self._agent = agent
            self._source = source
            self._sink.push(f"[ui] agent started dry_run={dry_run}")
            self._ready.set()
            loop.run_until_complete(agent.run())
        except Exception as exc:
            self._sink.push(f"[ui] agent failed: {exc!r}")
            self._ready.set()
        finally:
            try:
                loop.run_until_complete(loop.shutdown_asyncgens())
            finally:
                loop.close()


# ---------------------------------------------------------------- manual actions

MANUAL_ACTIONS = {
    "retrain": {"strategy": "full"},
    "rollback": {"target": "last_stable"},
    "arch_switch": {"target_arch": "lightgbm"},
    "scale_up": {"factor": 1.5},
}


def dispatch_manual(redis_url: str, kind: str) -> str:
    """Ручное действие оператора — напрямую в stream, без порогов."""
    if not redis_url:
        return "нет Redis URL"
    from datetime import datetime, timezone

    from kai9000.queue import StreamActionQueue

    payload = MANUAL_ACTIONS.get(kind)
    if payload is None:
        return f"неизвестное действие {kind}"
    action_id = f"manual-{kind}-{int(datetime.now(timezone.utc).timestamp())}"

    async def _send() -> bool:
        q = StreamActionQueue(redis_url)
        try:
            return await q.enqueue(kind, payload, action_id)
        finally:
            await q.aclose()

    ok = asyncio.run(_send())
    return f"{kind}: {'отправлено' if ok else 'ошибка'} ({action_id})"


# ---------------------------------------------------------------- UI

class RemoteRoot(BoxLayout):
    status = StringProperty("остановлен")
    dry_run = BooleanProperty(True)

    def __init__(self, **kwargs) -> None:
        super().__init__(orientation="vertical", padding=12, spacing=8, **kwargs)
        self.sink = _LogSink()
        self.host = AgentHost(self.sink)
        self._settings = load_settings()

        self.add_widget(Label(text="KAI9000", size_hint_y=None, height=36,
                              font_size=22, bold=True))

        self.redis_input = TextInput(
            text=self._settings.get("redis_url", ""),
            hint_text="redis://127.0.0.1:6379/0 или rediss://host:6380/0",
            multiline=False, size_hint_y=None, height=44,
        )
        self.add_widget(self.redis_input)

        row = BoxLayout(size_hint_y=None, height=44, spacing=8)
        row.add_widget(Label(text="dry_run"))
        self.switch = Switch(active=bool(self._settings.get("dry_run", True)))
        self.switch.bind(active=self._on_dry_run)
        row.add_widget(self.switch)
        self.add_widget(row)

        controls = BoxLayout(size_hint_y=None, height=48, spacing=8)
        start = Button(text="Старт")
        start.bind(on_release=self._on_start)
        stop = Button(text="Стоп")
        stop.bind(on_release=self._on_stop)
        controls.add_widget(start)
        controls.add_widget(stop)
        self.add_widget(controls)

        manual = BoxLayout(size_hint_y=None, height=48, spacing=6)
        for kind in MANUAL_ACTIONS:
            btn = Button(text=kind)
            btn.bind(on_release=lambda _b, k=kind: self._on_manual(k))
            manual.add_widget(btn)
        self.add_widget(manual)

        self.status_label = Label(text=f"статус: {self.status}",
                                  size_hint_y=None, height=28)
        self.add_widget(self.status_label)

        self.log_label = Label(text="", size_hint_y=None, halign="left",
                               valign="top", font_size=11)
        self.log_label.bind(width=lambda i, w: setattr(i, "text_size", (w, None)))
        scroll = ScrollView()
        scroll.add_widget(self.log_label)
        self.add_widget(scroll)

        root_logger = logging.getLogger()
        root_logger.setLevel(logging.INFO)
        root_logger.addHandler(_UiLogHandler(self.sink))
        Clock.schedule_interval(self._tick, 0.5)

    # --- handlers ---

    def _on_dry_run(self, _switch, value: bool) -> None:
        self.dry_run = bool(value)
        self._persist()

    def _persist(self) -> None:
        save_settings({"redis_url": self.redis_input.text.strip(),
                       "dry_run": bool(self.switch.active)})

    def _on_start(self, *_args) -> None:
        self._persist()
        url = self.redis_input.text.strip()
        if self.host.running:
            self.sink.push("[ui] агент уже запущен")
            return
        self.status = "запуск…"
        self.host.start(url, bool(self.switch.active))
        self.status = "работает" if self.host.running else "ошибка старта"

    def _on_stop(self, *_args) -> None:
        self.host.stop()
        self.status = "остановлен"
        self.sink.push("[ui] агент остановлен")

    def _on_manual(self, kind: str) -> None:
        url = self.redis_input.text.strip()
        try:
            self.sink.push(f"[ui] {dispatch_manual(url, kind)}")
        except Exception as exc:
            self.sink.push(f"[ui] manual {kind} failed: {exc!r}")

    def _tick(self, _dt: float) -> None:
        self.status_label.text = f"статус: {self.status}"
        lines = self.sink.drain()
        if not lines:
            return
        merged = (self.log_label.text + "\n" + "\n".join(lines)).strip()
        tail = merged.splitlines()[-LOG_LIMIT:]
        self.log_label.text = "\n".join(tail)


class Kai9000App(App):
    title = "KAI9000"

    def build(self) -> RemoteRoot:
        return RemoteRoot()

    def on_stop(self) -> None:
        root = self.root
        if isinstance(root, RemoteRoot):
            root.host.stop()
            root._persist()


if __name__ == "__main__":
    Kai9000App().run()
