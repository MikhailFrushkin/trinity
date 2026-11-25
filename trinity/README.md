# Тринити

Android-приложение постоянно слушает фразу **«Ей, Тринити»** на устройстве. После активации пишет команду и отправляет WAV на Python-сервер (faster-whisper). Одно слово «Тринити» запись не включает.

## Сервер

```powershell
cd trinity\server
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
copy .env.example .env
python run.py
```

Проверка: `GET http://127.0.0.1:8000/health`

Тесты:

```powershell
cd trinity\server
.\.venv\Scripts\Activate.ps1
pytest
```

Первый запуск скачает модель Whisper (`small`). Телефон и компьютер должны быть в одной Wi‑Fi сети. В приложении укажите `http://IP-компьютера:8000` (не `127.0.0.1`). Для эмулятора Android адрес хоста: `http://10.0.2.2:8000`.

## Android

1. Откройте папку `trinity/android` в Android Studio.
2. Дождитесь синхронизации Gradle (wrapper подтянется сам).
3. Соберите APK на реальное устройство (микрофон эмулятора обычно слабый).
4. Выдайте микрофон и уведомления.
5. Отключите оптимизацию батареи, когда система спросит.
6. Нажмите «Слушать». При первом старте скачается русская модель Vosk (~45 МБ).
7. Скажите «Ей, Тринити», затем команду.

## API

`POST /v1/utterance` — multipart: `audio`, `device_id`, `session_id`.

Ответ:

```json
{
  "transcript": "...",
  "reply_text": "Принято. Действия пока не подключены.",
  "intent": "unknown",
  "actions": []
}
```
