# Отчёт: аудит, план, работы и проверки — NeuroAgent

Дата: 2026-03-25  
Объект: архив `workspace-01a11dc6-6928-7e0a-aa71-03fc1285119f.zip` → проект `NeuroAgent/`  
Лицензия проекта: GPL-3.0-only

---

## 1. Аудит

### 1.1. Что в архиве

Архив — не «чистый» репозиторий. В нём:

| Путь | Оценка |
|---|---|
| `NeuroAgent/` | Исходники Android-агента (Kotlin, JNI, Gradle, тесты, F-Droid-метаданные) |
| `.android/` | Кэш Android SDK + **`debug.keystore`** |
| `.kotlin/daemon/` | Служебные файлы Kotlin daemon |

`debug.keystore` — секрет подписи debug-сборок. В рабочую копию извлечён **только** `NeuroAgent/`. Keystore и SDK-кэш не копировались.

Отсутствуют в копии репозитория (ожидаемо): `third_party/llama.cpp`, `gradlew.bat`, `gradle/verification-metadata.xml`.

### 1.2. Состояние продукта до правок

Код этапов 1–5 из `PLAN.md` / `ROADMAP.md` в основном написан: чат, SSE, tool calling, Accessibility, Shizuku, локальный GGUF-мост, юнит-тесты ядра. Не закрыты: нативная сборка `.so` в этой среде, APK, reproducible-проверка, тесты на устройстве, F-Droid submission.

### 1.3. Найденные дефекты (до исправления)

**Критические**

1. **Параллельные `tool_calls` ломались.** В `OpenAiClient.parseChunk` индекс брался через `JsonPrimitive.contentOrNull`. OpenAI шлёт `"index": 1` как JSON-число; `contentOrNull` для чисел даёт `null` → все вызовы сливались в слот 0. Тесты не ловили это: единственный вызов всегда был с индексом 0.
2. **Release-сборка убивала локальную модель.** R8 не держал `NativeLlama`; JNI-имена `Java_com_neuroagent_llm_NativeLlama_{load,generate,free}` после minify не находились бы.
3. **Accessibility включал touch exploration.** `flagRequestTouchExplorationMode` переводит устройство в режим TalkBack (двойной тап). Сервис агента делал бы телефон неудобным в обычном использовании.

**Высокий приоритет**

4. `ChatViewModel` ловил `Exception` и показывал ошибку при нажатии «Стоп» (`CancellationException`).
5. Поле чата на `remember` — текст терялся при повороте (`ROADMAP`).
6. Нет `clickByResourceId` / `click_id` (`ROADMAP`).
7. Узлы Accessibility не recycle-ились в `clickByText` / `inputText`.
8. Локальная GGUF-модель не выгружалась при нехватке памяти и закрытии Activity.
9. Неиспользуемый `okhttp-logging` в зависимостях (лишнее для F-Droid-аудита).
10. `FOREGROUND_SERVICE` в манифесте без соответствующего сервиса.
11. HTTP к локальному Ollama/LM Studio блокировался cleartext-политикой Android 9+.
12. API-ключи в настройках отображались открытым текстом.
13. `scripts/check_no_proprietary.sh` падал бы на `gradle-wrapper.jar`.
14. `scripts/fetch_llama.sh` не сверял commit hash, только печатал его.
15. На Windows не было `gradlew.bat`.
16. Кнопка «Стоп» не отменяла OkHttp-вызов до конца чтения (блокирующий `execute()`).

**Средний приоритет (частично закрыто)**

17. Нет журнала shell/Shizuku-команд (`ROADMAP`).
18. Markdown не рисовал нумерованные списки.
19. В терминале `KeyboardActions(onSend)` без `ImeAction.Send` — Enter не отправлял команду.

**Не закрыто в этой среде (ограничения машины)**

- Сборка APK / `libneuro_llama.so` (нет JDK 17, Android SDK, NDK).
- `gradle/verification-metadata.xml`.
- End-to-end на устройстве: Wi-Fi, Accessibility, Shizuku, GGUF.
- Подтверждение опасных команд перед выполнением (в `PLAN.md` — v1.1).
- URL репозитория в `fdroid/com.neuroagent.yml` всё ещё `example.invalid`.

---

## 2. План работ (выполненный)

1. Исправить парсинг индекса `tool_calls` + регрессионный тест.
2. Сделать SSE отменяемым без потери токенов (`callbackFlow` + `trySendBlocking`).
3. ProGuard keep для JNI.
4. Accessibility: убрать touch exploration, recycle, `click_id`.
5. Чат: `rememberSaveable`, корректный Stop, IME Send.
6. Выгрузка локальной модели (`ProviderRouter.release`).
7. Журнал команд, маскирование API key.
8. Cleartext для пользовательских endpoint, убрать мёртвые permission/deps.
9. Скрипты аудита/fetch llama, `gradlew.bat`, `vcsInfo.include = false`.
10. Проверки, которые возможно запустить без JDK/SDK.

---

## 3. Что сделано в коде

| Файл | Изменение |
|---|---|
| `ai/OpenAiClient.kt` | `parseToolCallIndex` (число и строка); SSE через `callbackFlow` + `trySendBlocking`; отмена = `call.cancel()` |
| `llm/OpenAiClientTest.kt` | Тест параллельных индексов 0/1 и строкового `"2"` |
| `proguard-rules.pro` | keep `NativeLlama` + `Function1` |
| `res/xml/accessibility_service_config.xml` | Убран touch exploration; добавлен `flagIncludeNotImportantViews` |
| `system/NeuroAccessibilityService.kt` | recycle, поиск по text/desc/id, `clickByResourceId` |
| `tools/Tools.kt` | action `click_id`; журнал; destroy процесса в `finally` |
| `settings/SettingsStore.kt` | `appendAuditLog` / `auditLog` / `clearAuditLog` |
| `llm/ProviderRouter.kt` | `release()` |
| `NeuroAgentApp.kt` | `onTrimMemory` / `onLowMemory` → `router.release()` |
| `MainActivity.kt` | `release()` при `isFinishing`; убраны мёртвые импорты |
| `ui/ChatViewModel.kt` | не глотает `CancellationException` |
| `ui/ChatScreen.kt` | `rememberSaveable`, IME Send |
| `ui/SettingsScreen.kt` | Password field, секция журнала |
| `ui/TerminalScreen.kt` | IME Send, не гоняет команды при выключенном shell |
| `ui/Markdown.kt` | нумерованные списки `1. ...` |
| `system/ShizukuManager.kt` | cancel stderr-job на таймауте, destroy в `finally` |
| `AndroidManifest.xml` | networkSecurityConfig; убран `FOREGROUND_SERVICE` |
| `res/xml/network_security_config.xml` | cleartext для пользовательских HTTP endpoint |
| `app/build.gradle.kts` | без logging-interceptor; `vcsInfo.include = false` |
| `gradle/libs.versions.toml` | удалён неиспользуемый `okhttp-logging` |
| `scripts/check_no_proprietary.sh` | исключены wrapper / build / .git |
| `scripts/fetch_llama.sh` | сверка commit `de7fa0a3…` |
| `gradlew.bat` | обёртка Gradle для Windows |

---

## 4. Тестирование

### 4.1. Что запускалось

Среда: Windows, **нет JDK, нет `JAVA_HOME`, нет Android SDK/NDK, нет git в PATH**. Есть Python 3.13.

Поэтому **не запускались**:

- `./gradlew :app:testReleaseUnitTest` (ожидаемые 12 тестов: прежние 11 + 1 новый)
- `assembleDebug` / `assembleRelease`
- нативная сборка llama.cpp
- прогон на устройстве/эмуляторе

Запускалась статическая проверка исходников (Python):

- скан build-файлов и `app/src` на GMS/Firebase/трекеры — чисто;
- нет vendored `.so/.aar/.jar` вне `gradle/wrapper`;
- все целевые правки присутствуют в файлах;
- логика индексов 0/1/`"2"` — `[0, 1, 2]`.

Результат: `RESULT PASSED`.

### 4.2. Как прогнать юнит-тесты у себя

Нужны JDK 17 и Android SDK 35:

```bat
cd NeuroAgent
gradlew.bat --no-daemon :app:testReleaseUnitTest
```

Ожидается 12 тестов (было 11: AgentLoop 3, SSE 2, аккумулятор 2, LocalToolCallParser 2, OpenAiClient 2; добавлен `parsesNumericIndexesForParallelToolCalls`).

---

## 5. Ограничения

1. Юнит-тесты Kotlin **не выполнены** в этой сессии: нет JDK 17.
2. APK и `.so` **не собраны**: нет SDK/NDK и RAM-профиля, описанного в `PLAN.md`.
3. Поведение на устройстве (Wi-Fi через Shizuku, дерево Accessibility, GGUF) **не проверялось**.
4. `debug.keystore` из исходного ZIP не использовался и не копировался.
5. Подтверждение опасных shell-команд перед запуском **не делалось** (запланировано в v1.1).
6. URL репозитория в F-Droid-метаданных по-прежнему заглушка — нужен реальный git remote.

---

## 6. Рекомендации дальше

1. На машине с JDK 17 + SDK 35: `testReleaseUnitTest`, затем `assembleRelease`.
2. Закрыть llama.cpp submodule (`scripts/fetch_llama.sh` или `git submodule update --init`).
3. Сценарии на устройстве: «включи Wi-Fi», клик по «Настройки», `dumpsys battery` через Shizuku.
4. Сгенерировать `gradle/verification-metadata.xml`.
5. Заменить `example.invalid` в `fdroid/com.neuroagent.yml`.
6. v1.1: подтверждение опасных команд в UI перед `shell_executor` / `shizuku_controller`.
