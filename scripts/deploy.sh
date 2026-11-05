#!/usr/bin/env bash
# scripts/deploy.sh — сборка образа, Secret, apply, cluster-checks.
# Запуск на Linux-хосте с docker и kubectl:
#   GHCR_PAT=ghp_xxx REDIS_URL=redis://... KFP_TOKEN=... ./scripts/deploy.sh
#
# Порядок жёсткий, проверки после каждого шага. Провал шага — выход.
set -euo pipefail

NS="${NS:-ml-system}"
DEPLOY="${DEPLOY:-kai9000-worker}"
IMAGE="${IMAGE:-ghcr.io/dimonich9580/kai9000-worker:1.0.0}"
GHCR_USER="${GHCR_USER:-Dimonich9580}"
REDIS_URL="${REDIS_URL:-}"
KFP_TOKEN="${KFP_TOKEN:-}"
CLUSTER_ALERT="${CLUSTER_ALERT:-}"

step() { printf '\n[deploy] %s\n' "$*"; }
die()  { printf '[deploy][FAIL] %s\n' "$*" >&2; exit 1; }

# -- 0. Проверка окружения -------------------------------------------
step "0. Проверка окружения"
command -v docker  >/dev/null || die "docker не найден"
command -v kubectl >/dev/null || die "kubectl не найден"
kubectl version --client >/dev/null 2>&1 || die "kubectl не отвечает"
kubectl get ns "$NS" >/dev/null 2>&1 || die "namespace $NS недоступен (KUBECONFIG?)"
[ -f cluster/Dockerfile.worker ] || die "cluster/Dockerfile.worker не найден"
[ -f k8s-cluster-worker.yaml ]   || die "k8s-cluster-worker.yaml не найден"
echo "[deploy] ok: окружение на месте"

# -- 1. Сборка и push образа -----------------------------------------
step "1. Сборка образа $IMAGE"
docker build -f cluster/Dockerfile.worker -t "$IMAGE" . || die "docker build"

step "1a. pydantic-settings внутри образа (cluster-checks шаг 1 смотрит на хост, не на образ)"
ps_ver="$(docker run --rm --entrypoint python "$IMAGE" -m pip show pydantic-settings 2>/dev/null \
    | awk -F': ' '/^Version:/{print $2; exit}')"
[ -n "$ps_ver" ] || die "pydantic-settings не установлен в образе"
[ "${ps_ver%%.*}" = "2" ] || die "pydantic-settings $ps_ver в образе, нужен 2.x (requirements.txt: >=2,<3)"
echo "[deploy] ok: pydantic-settings $ps_ver в образе"

step "1b. Push образа (нужен GHCR_PAT)"
[ -n "${GHCR_PAT:-}" ] || die "GHCR_PAT не задан"
echo "$GHCR_PAT" | docker login ghcr.io -u "$GHCR_USER" --password-stdin || die "docker login"
docker push "$IMAGE" || die "docker push"
docker manifest inspect "$IMAGE" >/dev/null 2>&1 || die "образ не виден в registry после push"
echo "[deploy] ok: $IMAGE в registry"

# -- 2. Secret и apply -----------------------------------------------
step "2. Secret ${DEPLOY}"
[ -n "$REDIS_URL" ] || die "REDIS_URL не задан"
kubectl -n "$NS" create secret generic "$DEPLOY" \
    --from-literal=KAI9000_REDIS_URL="$REDIS_URL" \
    --from-literal=KUBEFLOW_TOKEN="$KFP_TOKEN" \
    --from-literal=KAI9000_CLUSTER_ALERT_WEBHOOK="$CLUSTER_ALERT" \
    --dry-run=client -o yaml | kubectl apply -f - || die "secret apply"

step "2b. Apply k8s-cluster-worker.yaml"
kubectl apply -f k8s-cluster-worker.yaml || die "kubectl apply"

step "2c. Ожидание готовности деплоймента"
kubectl -n "$NS" rollout status "deploy/$DEPLOY" --timeout=180s || {
    kubectl -n "$NS" get pods -l app="$DEPLOY" -o wide || true
    kubectl -n "$NS" describe deploy "$DEPLOY" | tail -40 || true
    die "rollout не завершился"
}

# -- 3. Cluster checks -----------------------------------------------
step "3. ./cluster-checks.sh --only 2,3,4 (шаг 1 проверен выше, на образе)"
[ -x cluster-checks.sh ] || chmod +x cluster-checks.sh
checks_rc=0
for n in 2 3 4; do
    NS="$NS" DEPLOY="$DEPLOY" ./cluster-checks.sh --only "$n" || checks_rc=1
done
[ "$checks_rc" -eq 0 ] || die "cluster-checks: есть провалы, см. вывод. Шаг 3 ветка B - решение B1/B2; 401/403 - Secret/RBAC; PONG/метки/CIDR - манифест."
echo "[deploy] cluster-checks: шаги 2-4 зелёные"

echo
echo "[deploy] готово. PR #3 можно снимать с draft и мержить (fast-forward)."
echo "[deploy] откат: kubectl -n $NS delete deploy/$DEPLOY; kubectl -n $NS delete secret $DEPLOY"
