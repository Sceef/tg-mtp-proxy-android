# TG WS Proxy (Android)

Локальный **MTProto → WebSocket** мост для сценариев совместимости с **Telegram Desktop** (`tg://proxy`). Трафик шифруется как у Telegram; сторонние серверы не используются.

```
Клиент с MTProto proxy → 127.0.0.1:PORT → WSS → Telegram DC
```

## Репозиторий

- **`android/`** — продукт: Kotlin-приложение (Gradle). Сборка debug APK: из каталога `android` выполнить `./gradlew assembleDebug` (нужны **JDK 17+** и Android SDK; проще открыть проект в **Android Studio**).
- **`reference/python/`** — эталонная реализация протокола на Python (для сверки и тестов). См. [reference/README.md](reference/README.md).

Артефакты можно выкладывать в **GitHub Releases** (CI: workflow `.github/workflows/android.yml` собирает debug APK).

## Ограничение

Официальный **Telegram для Android** не подключается к локальному MTProto-прокси так же, как Desktop. Целевой клиент для ссылки `tg://proxy` — **Telegram Desktop** (или иной клиент с тем же типом прокси).

## Лицензия

См. [LICENSE](LICENSE).
