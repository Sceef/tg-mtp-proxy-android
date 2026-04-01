# TG WS Proxy (Android)

Локальный **MTProto** для android версии Telegram.

## Репозиторий

- **`android/`** — продукт: Kotlin-приложение (Gradle). Модуль **`android/core`** — чистая JVM-логика (handshake, relay, AES-CTR, RawWebSocket, пул WS, TCP fallback); юнит-тесты: `./gradlew :core:test`. Сборка APK: `./gradlew :app:assembleDebug` (нужны **JDK 17+** и Android SDK; удобно открыть `android/` в **Android Studio**). Приложение поднимает **foreground service** с уведомлением, слушает настроенный `host:port`, хранит secret и DC→IP в **DataStore**, кнопка копирует ссылку `tg://proxy`.
- **`reference/python/`** — эталон: [reference/python/proxy/tg_ws_proxy.py](reference/python/proxy/tg_ws_proxy.py). Скрипты золотых векторов: `reference/python/gen_golden_handshake.py`, `gen_golden_relay.py` (должны совпадать с тестами в `android/core`).

### Сборка и релиз на GitHub

- CI: [.github/workflows/android.yml](.github/workflows/android.yml) — на каждый push/PR: `:core:test` и debug APK как артефакт.
- **Release:** создайте тег вида `v1.0.0` и отправьте в репозиторий; job `release` прикрепит `app-debug.apk` к GitHub Release (подпись release-сборки при необходимости добавьте локально).

### Интеграция с эталонным Python (опционально)

На ПК запустите прокси из `reference/python`, на эмуляторе Android включите прокси приложения на тот же порт и с тем же secret, либо пробросьте порт:

`adb reverse tcp:1443 tcp:1443`

Трафик с хоста на `127.0.0.1:1443` попадёт в сокет прокси внутри эмулятора. Сверяйте логи эталона и приложения при одинаковых DC и secret.

## Лицензия

См. [LICENSE](LICENSE).
