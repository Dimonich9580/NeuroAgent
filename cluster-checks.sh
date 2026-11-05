#!/usr/bin/env bash
# cluster-checks.sh — четыре проверки перед включением воркера.
# Шаг 1 блокирует остальные: без pydantic-settings 2.x воркер не стартует.
#
#   NS=ml-system DEPLOY=kai9000-worker ./cluster-checks.sh
#   ./cluster-checks.sh --only 2
#   APPLY_POLICY=1 ./cluster-checks.sh
set -uo pipefail

NS="${NS:-ml-system}"
DEPLOY="${DEPLOY:-kai9000-worker}"
KUBEFLOW_URL="${KUBEFLOW_URL:-http://ml-pipeline.ml-pipeline.svc.cluster.local:8888}"
KUBEFLOW_TOKEN="${KUBEFLOW_TOKEN:-}"
REDIS_HOST="${REDIS_HOST:-redis.${NS}.svc.cluster.local}"
REDIS_PORT="${REDIS_PORT:-6379}"
ONLY=""
APPLY_POLICY="${APPLY_POLICY:-0}"

while [ $# -gt 0 ]; do
    case "$1" in
        --only) ONLY="${2:-}"; shift 2 ;;
        --only=*) ONLY="${1#*=}"; shift ;;
        -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done

if [ -t 1 ]; then
    R=$'\e[31m'; G=$'\e[32m'; Y=$'\e[33m'; B=$'\e[36m'; N=$'\e[0m'
else
    R=""; G=""; Y=""; B=""; N=""
fi

ok()   { printf '%s[ OK ]%s %s\n' "$G" "$N" "$*"; }
warn() { printf '%s[WARN]%s %s\n' "$Y" "$N" "$*"; }
fail() { printf '%s[FAIL]%s %s\n' "$R" "$N" "$*"; }
info() { printf '%s[INFO]%s %s\n' "$B" "$N" "$*"; }

want() { [ -z "$ONLY" ] || [ "$ONLY" = "$1" ]; }

# ---------------------------------------------------------------- шаг 1

step1() {
    info "шаг 1: pydantic-settings в образе"
    if ! command -v python >/dev/null 2>&1; then
        warn "python не найден — шаг 1 пропущен (не кластер?)"
        return 0
    fi
    local out ver major
    if ! out="$(python -m pip show pydantic-settings 2>/dev/null)"; then
        fail "pydantic-settings не установлен — пересобери образ с >=2,<3"
        return 1
    fi
    ver="$(printf '%s\n' "$out" | awk -F': ' '/^Version:/{print $2; exit}')"
    major="${ver%%.*}"
    if [ "$major" = "2" ]; then
        ok "pydantic-settings ${ver}"
        return 0
    fi
    if [ "$major" = "1" ]; then
        fail "pydantic-settings ${ver} — это 1.x; KAI9000Config требует 2.x (SettingsConfigDict)"
        warn "либо >=2,<3 в образе, либо откат config.py на v1 API"
        return 1
    fi
    fail "не удалось определить версию: '${ver}'"
    return 1
}

# ---------------------------------------------------------------- шаг 2

step2() {
    info "шаг 2: KFP API ${KUBEFLOW_URL}/apis/v2beta1/runs"
    if ! command -v curl >/dev/null 2>&1; then
        warn "curl не найден — шаг 2 пропущен"
        return 0
    fi
    local code
    local args=(-s -o /dev/null -w '%{http_code}' --max-time 10
                "${KUBEFLOW_URL}/apis/v2beta1/runs")
    [ -n "$KUBEFLOW_TOKEN" ] && args+=(-H "Authorization: Bearer ${KUBEFLOW_TOKEN}")
    code="$(curl "${args[@]}" 2>/dev/null || echo 000)"
    case "$code" in
        200) ok "KFP отвечает 200" ;;
        401) fail "401 — Secret/oidc: токен не принят (KUBEFLOW_TOKEN или SA JWT namespace Kubeflow)"; return 1 ;;
        403) fail "403 — RBAC: сервис-аккаунту воркера не выдан доступ к KFP"; return 1 ;;
        000) fail "000 — DNS/сеть: имя сервиса не резолвится или порт закрыт"; return 1 ;;
        *)   fail "неожиданный код ${code}"; return 1 ;;
    esac
}

# ---------------------------------------------------------------- шаг 3

step3() {
    info "шаг 3: HPA против ${DEPLOY}"
    local out
    if ! out="$(kubectl -n "$NS" get hpa 2>/dev/null)"; then
        fail "kubectl get hpa не выполнился — проверь контекст и RBAC"; return 1
    fi
    if printf '%s\n' "$out" | grep -q '^No resources found'; then
        ok "ветка A: HPA нет — _k8s_scale правит Deployment.scale напрямую"
        return 0
    fi
    printf '%s\n' "$out"
    if printf '%s\n' "$out" | awk 'NR>1{print $1}' | grep -qx "$DEPLOY"; then
        warn "ветка B: HPA уже управляет ${DEPLOY}"
        warn "B1: kubectl -n ${NS} delete hpa <name>   (ручное масштабирование)"
        warn "B2: переписать _k8s_scale на PATCH"
        warn "    /apis/autoscaling/v2/namespaces/${NS}/horizontalpodautoscalers/<name>"
        warn "    spec.minReplicas; RBAC: horizontalpodautoscalers (было */scale)"
        return 0
    fi
    ok "ветка C: HPA есть, но не на ${DEPLOY} — конфликта нет"
}

# ---------------------------------------------------------------- шаг 4

step4() {
    info "шаг 4: NetworkPolicy и связность"
    local policies fails=0
    policies="$(kubectl -n "$NS" get networkpolicy -o name 2>/dev/null | wc -l | tr -d ' ')"
    if [ "$policies" = "0" ]; then
        warn "NetworkPolicy нет: поды пускают всех в namespace"
        if [ "$APPLY_POLICY" = "1" ]; then
            if [ -f k8s-networkpolicy.yaml ]; then
                # Подставляем реальный IP API-сервера вместо плейсхолдера.
                local api_ip
                api_ip="$(kubectl get svc kubernetes -n default \
                    -o jsonpath='{.spec.clusterIP}' 2>/dev/null)"
                if [ -z "$api_ip" ]; then
                    fail "Не удалось получить clusterIP kubernetes.default — политика не применена"
                    fails=$((fails + 1))
                else
                    local tmp_np
                    tmp_np="$(mktemp)"
                    sed "s|__KUBE_API_CIDR__|${api_ip}|g" k8s-networkpolicy.yaml > "$tmp_np"
                    if grep -q '__KUBE_API_CIDR__' "$tmp_np"; then
                        rm -f "$tmp_np"
                        fail "Плейсхолдер __KUBE_API_CIDR__ остался после подстановки"
                        fails=$((fails + 1))
                    elif kubectl apply -f "$tmp_np"; then
                        ok "k8s-networkpolicy.yaml применён (API IP=${api_ip})"
                    else
                        fail "kubectl apply не удался"
                        fails=$((fails + 1))
                    fi
                    rm -f "$tmp_np"
                fi
            else
                warn "k8s-networkpolicy.yaml не найден рядом со скриптом"
            fi
        else
            info "APPLY_POLICY=1 ./cluster-checks.sh — применить политику"
        fi
    else
        ok "NetworkPolicy: ${policies} шт."
    fi

    # Redis: PING из пода воркера (или любого пода с redis-cli)
    local pod
    pod="$(kubectl -n "$NS" get pods -l app="$DEPLOY" \
            -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)"
    if [ -z "$pod" ]; then
        warn "пода ${DEPLOY} нет — PING пропущен"
    else
        if kubectl -n "$NS" exec "$pod" -- \
            python -c "import socket,sys; s=socket.create_connection(('${REDIS_HOST}',${REDIS_PORT}),5); s.sendall(b'PING\r\n'); sys.exit(0 if s.recv(16).startswith(b'+PONG') else 1)" \
            >/dev/null 2>&1; then
            ok "Redis PONG (${REDIS_HOST}:${REDIS_PORT})"
        else
            fail "Redis недоступен из пода ${pod}"; fails=$((fails + 1))
        fi
    fi

    # K8s API: SA-токен + CA из пода. Используем python (stdlib) вместо curl:
    # slim-образ воркера может не содержать curl, а python гарантирован.
    if [ -z "$pod" ]; then
        warn "K8s API из пода не проверен — пода нет"
    elif kubectl -n "$NS" exec "$pod" -- python3 -c '
import http.client, ssl, sys, os
try:
    token = open("/var/run/secrets/kubernetes.io/serviceaccount/token").read().strip()
    ca = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
    ctx = ssl.create_default_context(cafile=ca)
    conn = http.client.HTTPSConnection("kubernetes.default.svc", timeout=5, context=ctx)
    conn.request("GET", "/api", headers={"Authorization": f"Bearer {token}"})
    resp = conn.getresponse()
    sys.exit(0 if resp.status == 200 else 1)
except Exception as e:
    print(e, file=sys.stderr); sys.exit(1)
' >/dev/null 2>&1; then
        ok "K8s API 200"
    else
        warn "K8s API не отвечает 200 из пода — проверь Service CIDR в NetworkPolicy"
        fails=$((fails + 1))
    fi

    # KServe: список InferenceService
    if kubectl -n "$NS" get inferenceservices.serving.kserve.io >/dev/null 2>&1; then
        ok "KServe CRD доступен (200 по RBAC)"
    else
        warn "KServe CRD недоступен — проверь метки и RBAC"
        fails=$((fails + 1))
    fi

    [ "$fails" -eq 0 ]
}

# ---------------------------------------------------------------- main

run_step() {
    case "$1" in
        1) step1 ;;
        2) step2 ;;
        3) step3 ;;
        4) step4 ;;
    esac
}

rc=0
if [ -n "$ONLY" ]; then
    run_step "$ONLY" || rc=$?
    exit "$rc"
fi

if ! command -v kubectl >/dev/null 2>&1; then
    warn "kubectl не найден — ./cluster-checks.sh не для локальной машины"
    warn "для локальной проверки: py -m py_compile ... && py smoke.py"
    warn "для кластера: скопировать на Linux-хост с kubectl или запустить scripts/deploy.sh"
    exit 0
fi

if ! step1; then
    fail "шаг 1 провален — остальные не запускаются"
    exit 1
fi
for n in 2 3 4; do
    run_step "$n" || rc=1
done
[ "$rc" -eq 0 ] && ok "все проверки пройдены" || fail "есть провалы"
exit "$rc"
