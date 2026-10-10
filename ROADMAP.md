# ROADMAP.md: разработка NeuroAgent

> **Статус:** код всех этапов написан. Не выполнены: нативная сборка `.so`, сборка APK, reproducible-проверка, тесты на устройстве.
> Подробный фактический статус и проверки: см. [PLAN.md](PLAN.md), раздел 5.
> **Цель:** автономный AI-агент для Android с Tool Calling, локальной LLM и публикацией в F-Droid.
> **Срок:** 21 день (3 недели).

Обозначения: `[x]` сделано и проверено, `[~]` написано, но не проверено на сборке или устройстве, `[ ]` не сделано.

---

## Этап 0: Подготовка окружения (день 1)

- [~] JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1 (в песочнице установлены, Android Studio не ставилась)
- [x] Проект Kotlin + Jetpack Compose (Kotlin DSL)
- [x] `gradle/libs.versions.toml` с фиксированными версиями: AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.12.01, OkHttp 4.12.0, kotlinx.serialization 1.7.3, Shizuku 13.1.5
- [x] `.gitignore` (`.gradle/`, `build/`, `*.apk`, `local.properties`, `*.jks`)
- [x] `README.md`
- [x] `LICENSE` (GPLv3, текст с gnu.org)
- [x] Лицензионные заголовки во всех `.kt` и `.cpp`
- [ ] `./gradlew assembleDebug` без варнингов. Собирается Kotlin-часть (`compileReleaseKotlin`); полная сборка с CMake не завершена из-за памяти песочницы
- [x] Нет зависимостей GMS, Firebase, Crashlytics (проверяет `scripts/check_no_proprietary.sh`, аудит пройден)
- [x] Структура: `app/src/main/java/com/neuroagent/`, `app/src/main/cpp/`, `scripts/`, `fdroid/`

> Предупреждение о GMS, Firebase и Crashlytics учтено: скрипт аудита падает при любом упоминании.

---

## Этап 1: Фундамент и сеть (дни 2–4)

- [x] Навигация Navigation Compose: `Chat`, `Terminal`, `Settings`
- [~] UI чата: `LazyColumn`, поле ввода, стриминг, Markdown (заголовки, списки, `**bold**`, `` `code` ``, блоки кода). Код есть, визуально не проверен
- [x] Модели OpenAI Chat Completions (`ChatMessage`, `ToolCall`, `ToolSpec`, запрос с `tools` и `stream`). Используются доменные типы и JSON-маппинг в `OpenAiClient`, а не `data class` из ROADMAP, потому что так проще тестировать ядро без Android
- [x] OkHttp-клиент со SSE: парсинг `data: ...`, аккумуляция токенов, `tool_calls` в стриме (`SseParser`, `ToolCallAccumulator`, покрыто тестами)
- [~] `EncryptedSharedPreferences` для трёх облачных слотов (endpoint, key, model). Не проверено на устройстве
- [~] Экран настроек со слотами
- [ ] Реальный запрос к OpenRouter или другому endpoint (нужен ключ и устройство)

**DoD:**
- [ ] Запрос уходит на тестовый endpoint, ответ стримится в UI (нет проверки на устройстве)
- [~] Ключи в `EncryptedSharedPreferences`, в логи не пишутся (в коде `Log` не используется)

---

## Этап 2: Глаза и руки (дни 5–8)

- [~] `NeuroAccessibilityService`: дерево UI, клик по тексту, ввод текста, жесты, глобальные действия. ROADMAP просит `getScreenTree`, `clickByTextOrId`, `performGlobalAction`. Реализованы `dumpTree`, `clickByText`, `inputText`, `tap`, `swipe`; `clickByResourceId` не добавлен
- [x] `res/xml/accessibility_service_config.xml`. Конфиг сокращён: нужны только `typeWindowStateChanged`, `typeWindowContentChanged`, `typeViewClicked`, а не `typeAllMask`. Так меньше событий и меньше расход батареи
- [~] `getScreenTree()` в текстовом формате для LLM (класс, текст, описание, id, флаги, координаты). Не проверено на устройстве
- [~] Shizuku: зависимости `dev.rikka.shizuku:api` и `provider` 13.1.5, `ShizukuManager` с `isGranted`, `requestPermission`, `exec`. `Shizuku.newProcess` не публичный API, вызывается через рефлексию
- [~] Экран Settings с инструкциями и кнопками открытия системных настроек (инструкции короткие, полную документацию для пользователей ещё нужно написать)

**DoD:**
- [ ] Дерево экрана выводится в лог на устройстве
- [ ] Клик по тексту «Настройки»
- [ ] `dumpsys battery` через Shizuku

> **Предупреждение об OEM-прошивках:** учтено в README (раздел «Ограничения»). `foregroundService` не добавлен. AccessibilityService работает на системном уровне, и отдельный foreground-сервис ему не нужен. Если на конкретной прошивке процесс всё же убивают, добавлю сервис с уведомлением после теста на устройстве.

---

## Этап 3: Цикл агентности (дни 9–12)

- [x] Интерфейс `Tool`, `ToolRegistry` с JSON Schema и выбором только включённых инструментов
- [x] `AgentLoop`: запрос → стрим → `tool_calls` → выполнение → `role: tool` → повтор. Лимит `maxSteps = 5`, по умолчанию как в ROADMAP
- [x] Четыре инструмента: `screen_reader`, `action_executor`, `shell_executor` (`ProcessBuilder`, `sh -c`, stdout/stderr, таймаут 30 с, лимит вывода 16 КБ), `shizuku_controller`
- [x] Юнит-тесты цикла (вызов инструмента, неизвестный инструмент, лимит шагов)
- [~] UI: сворачиваемый блок рассуждений, карточки вызовов инструментов с результатами. Не проверено визуально

**DoD:**
- [ ] «Включи Wi-Fi» на устройстве. Пока есть только путь через `shizuku_controller` (`svc wifi enable`) и `action_executor`
- [ ] Повторный `screen_reader` для проверки результата. Логика есть в промпте, на устройстве не проверена
- [x] Цикл прерывается после 5 итераций (покрыто тестом)

---

## Этап 4: Локальный мозг (NDK + llama.cpp) (дни 13–18)

- [x] llama.cpp закреплён тегом `b11514` (коммит `de7fa0a3`). Тег новее, чем `b3456` из ROADMAP. Старый тег не использовался: API llama.cpp менялся, а JNI-слой написан под текущий. Подключение через `scripts/fetch_llama.sh` с проверкой коммита; submodule описан в `.gitmodules`
- [x] `CMakeLists.txt`: `libneuro_llama.so` поверх статической сборки llama
- [~] JNI-мост `neuro_llama_jni.cpp`. Проверен против реальных заголовков `llama.h` (синтаксис), но не собран через NDK. Функции: `load`, `generate` (стриминг токенов через callback, с корректной обработкой UTF-8), `free`
- [x] ROADMAP предлагал JNI-функции `generateToken` и `chatCompletion`. Выбрана схема «один вызов `generate` с callback на каждый токен». Так не нужно создавать строку на каждый токен и проще отменять генерацию
- [~] `LocalLlmClient` и маршрутизация через `ProviderRouter`. Не проверено на устройстве
- [x] Парсинг `<tool_call>` для локальных моделей (в ROADMAP этого нет, без него локальная модель не умеет вызывать инструменты). Покрыто тестами
- [~] Выбор `.gguf` через SAF с копированием в `filesDir`. Модель копируется, а не читается по URI, потому что llama.cpp нужен путь к файлу. Не проверено
- [~] Gradle-настройки NDK: `abiFilters` (`arm64-v8a`, `x86_64`), `-DANDROID_STL=c++_static`, `-DGGML_OPENMP=OFF`, NDK 27.2.12479018, CMake 3.22.1

**DoD:**
- [ ] Загрузка модели на устройстве. Не проверено
- [ ] Ответ на «Привет» без сети. Не проверено
- [ ] RAM ≤ 4–5 ГБ. Не замерено
- [~] Освобождение памяти: `NativeLlama.free` вызывается из `LocalLlmClient.release()`, которое вызывает роутер при смене модели. При закрытии приложения явного вызова нет. Это место нужно доработать

> **Предупреждение о моделях.** Qwen2.5-7B Q4_K_M весит около 4.7 ГБ, и на телефоне с 8 ГБ RAM он работает медленно. Для минимальной цели из ТЗ (8 ГБ+) рекомендую Qwen2.5-3B Q4_K_M по умолчанию, а 7B оставить опцией для устройств с 12 ГБ+. 70B не запускать.

---

## Этап 5: F-Droid purge и reproducible builds (дни 19–21)

- [x] `scripts/check_no_proprietary.sh`: проверяет build-файлы, исходники, манифест, наличие `.so`/`.aar`/`.jar` в репозитории и runtime-classpath Gradle. Аудит пройден
- [ ] `gradle/verification-metadata.xml` (`--write-verification-metadata sha256`). Не сгенерирован: это нужно делать на полном Gradle-проекте с доступом к репозиториям, в песочнице не хватило памяти
- [~] `.github/workflows/reproducible.yml`: аудит, юнит-тесты, две сборки в разных путях и сравнение SHA-256. Вместо варианта из ROADMAP (`rm -rf app/build` и пересборка в том же месте) выбран вариант с другим путём: он ловит утечку абсолютных путей. Не запускался
- [~] `fdroid/com.neuroagent.yml`. Адреса репозитория — заглушки `example.invalid`, их нужно заменить. `fdroid lint` не запускался
- [x] README: что делает приложение, какие разрешения нужны, риски, как собрать

**DoD:**
- [ ] CI зелёный. CI не запускался
- [ ] Хэши двух сборок совпадают. Не проверено
- [x] `check_no_proprietary.sh` не находит проприетарщины
- [ ] `fdroid lint`

---

## Финальный чек-лист перед релизом

- [ ] Форматирование ktlint или встроенным форматтером. Не запускалось
- [ ] Все TODO и FIXME перенесены в issues. Открытых: заглушки `example.invalid` в `fdroid/com.neuroagent.yml`, адрес репозитория в README
- [ ] Поворот экрана: `ChatViewModel` переживает поворот, но введённый текст в `ChatScreen` хранится в `remember` и теряется. Нужно заменить на `rememberSaveable`
- [~] Освобождение памяти модели при закрытии приложения (см. этап 4)
- [x] Лицензионные заголовки в каждом `.kt` и `.cpp`
- [ ] Тест на реальном устройстве
- [ ] Документация для пользователей: установка Shizuku, выбор модели. Сейчас только разделы в README

---

## Рекомендуемые модели для локального слота

| Устройство | Модель | Квантование | Размер | Комментарий |
|---|---|---|---|---|
| 8 ГБ RAM (минимум ТЗ) | Qwen2.5-3B-Instruct | Q4_K_M | ~2 ГБ | Рекомендуемый вариант по умолчанию |
| 12 ГБ+ RAM | Qwen2.5-7B-Instruct | Q4_K_M | ~4.7 ГБ | Лучше следует JSON Schema, но медленнее |
| Запасной | Llama-3.1-8B-Instruct | Q4_K_M | ~4.9 ГБ | Хуже в строгом JSON |

Источники: HuggingFace `Qwen/Qwen2.5-3B-Instruct-GGUF`, `Qwen/Qwen2.5-7B-Instruct-GGUF`, `bartowski/Meta-Llama-3.1-8B-Instruct-GGUF`. Версии файлов и размеры проверить перед загрузкой.

---

## Риски и предупреждения

1. **OEM убивают сервисы (MIUI, ColorOS, EMUI).** Документировано в README. Тест на устройстве пока не сделан.
2. **Shell-команды от LLM.** Меры: отдельное разрешение, выключено по умолчанию; таймаут 30 с; вывод ограничен 16 КБ. Нет журнала выполненных команд и подтверждения перед опасными командами. Журнал — следующая задача.
3. **Reproducible builds.** Зафиксированы JDK 17, `SOURCE_DATE_EPOCH`, `--no-build-cache`, `includeVcsInfo=false` через `dependenciesInfo`. Пока не проверено.
4. **llama.cpp меняется быстро.** Закреплён тег `b11514`, JNI изолирован в одном файле.
5. **`EncryptedSharedPreferences` deprecated.** Используется `security-crypto:1.1.0-alpha06`. Миграция на Tink или DataStore в backlog.

---

## Что дальше (по приоритету)

1. Полная сборка APK на машине с 8+ ГБ RAM или в CI. Первый реальный результат по нативной части.
2. Тест на Pixel или эмуляторе: дерево экрана, клик, Shizuku `dumpsys battery`.
3. Сценарий «включи Wi-Fi» end-to-end.
4. Замер RAM и скорости локальной модели.
5. Журнал shell-команд, `rememberSaveable`, gradle verification-metadata, `fdroid lint`.
