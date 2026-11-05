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

    # Дефолт writable без root: /var/lib/Dimonich недоступен в Termux
    # и в non-root контейнере. termux_entry переопределяет на ~/.kai9000/state.json,
    # кластерный воркер KAI9000Config не создаёт вообще.
    state_path: Path = Path.home() / ".kai9000" / "state.json"
    alert_webhook: Optional[str] = None
