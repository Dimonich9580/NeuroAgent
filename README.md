# NeuroAgent

Автономный AI-агент для Android, который по текстовому запросу управляет телефоном
(«включи Wi-Fi», «открой настройки и найди батарею»). Лицензия: **GPL-3.0-only**.
Распространение: F-Droid (метаданные в `fdroid/`).

Репозиторий: https://github.com/Dimonich9580/NeuroAgent  
Статус плана и этапов: см. [PLAN.md](PLAN.md).

## Возможности

- **AI-роутер:** OpenAI-совместимый API (OpenAI, OpenRouter, Ollama `/v1`, LM Studio, vLLM), 3 облачных слота и 1 локальный GGUF-слот (llama.cpp).
- **Стриминг** ответов (SSE) и **Tool Calling** (JSON Schema).
- **Инструменты агента:**
  - `screen_reader`: дерево UI через AccessibilityService;
  - `action_executor`: клик по тексту, по resource-id, тап, свайп, ввод текста, глобальные действия;
  - `shell_executor`: `sh -c` с stdout/stderr, таймаут 30 с, журнал команд;
  - `shizuku_controller`: команды с ADB-правами через Shizuku.
- **UI (Jetpack Compose, Material 3):** чат с Markdown, подсветкой кода и Chain of Thought; терминал с ANSI-цветами и историей; настройки с ключами, выбором GGUF и переключателями разрешений.

Все возможности, дающие доступ к устройству, **выключены по умолчанию** и включаются в Настройках → Разрешения.

## Структура

```
app/src/main/java/com/neuroagent/
  ai/        домен, SSE-парсер, аккумулятор tool_calls, OpenAI-клиент
  agent/     Tool, ToolRegistry, AgentLoop (цикл tool calling)
  llm/       ProviderRouter, LocalLlmClient (JNI), разбор <tool_call> для локальных моделей
  tools/     четыре инструмента агента
  system/    AccessibilityService, ShizukuManager
  settings/  EncryptedSharedPreferences
  ui/        чат, терминал, настройки, Markdown
app/src/main/cpp/   JNI-мост к llama.cpp + CMakeLists.txt
third_party/llama.cpp  исходники llama.cpp (тег b11514, см. scripts/fetch_llama.sh)
scripts/            аудит зависимостей, получение llama.cpp
fdroid/             метаданные для F-Droid
.github/workflows/  проверка воспроизводимости
```

## Сборка

Требования: JDK 17, Android SDK (platform 35, build-tools 35.0.0), NDK 27.2.12479018, CMake 3.22.1.

```bash
export SOURCE_DATE_EPOCH=1700000000 TZ=UTC LC_ALL=C.UTF-8
bash scripts/fetch_llama.sh          # или: git submodule update --init --recursive
./gradlew --no-daemon :app:testReleaseUnitTest
./gradlew --no-daemon :app:assembleRelease
bash scripts/check_no_proprietary.sh # аудит зависимостей
```

На Windows: `gradlew.bat` вместо `./gradlew`.

Release-APK не подписан: подпись добавляется отдельно (F-Droid подписывает сам).

## Воспроизводимость

CI (`.github/workflows/reproducible.yml`) собирает APK дважды в одном пути. Сравнивает SHA-256. Различие означает утечку пути, времени или окружения и блокирует релиз.

## Ограничения

- AccessibilityService на некоторых прошивках отключается системой. Включить его можно в «Спец. возможностях».
- Wi-Fi и Bluetooth на Android 10+ нельзя переключить обычным API приложения. Для этого нужен Shizuku (`svc wifi enable`).
- Локальная модель (GGUF, Q4_K_M) ориентирована на устройства с 8 ГБ+ RAM. Скорость зависит от SoC.
- Локальные модели не имеют нативного tool calling. Вызовы идут через теги `<tool_call>` в тексте (см. `LocalToolCallParser`).

## Безопасность

Агент может выполнять shell-команды и действия от имени пользователя. Ключи API хранятся в EncryptedSharedPreferences (AES256-GCM, Android Keystore). Сетевой трафик идёт только на endpoint, который указал пользователь.
