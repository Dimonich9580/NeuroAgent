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
