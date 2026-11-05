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
