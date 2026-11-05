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
