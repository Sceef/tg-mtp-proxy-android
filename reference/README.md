# Референс: Python-реализация прокси

Используется для сверки протокола и интеграционных тестов при разработке Android-приложения.

## Запуск

```bash
cd reference/python
pip install -r requirements.txt
python -m proxy.tg_ws_proxy --help
```

По умолчанию слушает `127.0.0.1:1443`, те же DC, что в `default_config.json`.
