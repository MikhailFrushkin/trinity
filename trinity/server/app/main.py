from __future__ import annotations

import json
import tempfile
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from loguru import logger

from app.config import settings
from app.intent_router import route
from app.logging_setup import setup_logging
from app.session_store import SessionStore
from app.transcriber import AudioTranscriber, WhisperTranscriber

setup_logging()


def create_app(transcriber: AudioTranscriber | None = None) -> FastAPI:
    engine = transcriber or WhisperTranscriber(settings.whisper_model)
    store = SessionStore()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.transcriber = engine
        app.state.sessions = store
        if not engine.ready:
            engine.load()
        yield

    app = FastAPI(title="Trinity STT", lifespan=lifespan)

    @app.middleware("http")
    async def log_http(request: Request, call_next):
        client = request.client.host if request.client else "-"
        logger.info(
            "http in: {} {} from {}",
            request.method,
            request.url.path,
            client,
        )
        try:
            response = await call_next(request)
        except Exception:
            logger.exception("http error: {} {} from {}", request.method, request.url.path, client)
            raise
        logger.info(
            "http out: {} {} -> {}",
            request.method,
            request.url.path,
            response.status_code,
        )
        return response

    @app.get("/health")
    async def health(request: Request) -> dict:
        current: AudioTranscriber = request.app.state.transcriber
        body = {"ok": True, "model_ready": current.ready}
        logger.info("health out: {}", json.dumps(body, ensure_ascii=False))
        return body

    @app.post("/v1/utterance")
    async def utterance(request: Request) -> JSONResponse:
        client = request.client.host if request.client else "-"
        form = await request.form()
        form_keys = [str(key) for key in form.keys()]
        logger.info(
            "utterance in: client={} content_type={} form_keys={}",
            client,
            request.headers.get("content-type"),
            form_keys,
        )
        audio = form.get("audio")
        if audio is None or not hasattr(audio, "read"):
            logger.warning("utterance reject: audio file is missing, form_keys={}", form_keys)
            raise HTTPException(status_code=400, detail="audio file is missing")

        device_id = str(form.get("device_id") or "unknown")
        session_id = str(form.get("session_id") or "default")
        filename = str(getattr(audio, "filename", "") or "clip.wav")
        suffix = Path(filename).suffix or ".wav"
        payload = await audio.read()
        if not payload:
            logger.warning(
                "utterance reject: empty audio filename={} device_id={} session_id={}",
                filename,
                device_id,
                session_id,
            )
            raise HTTPException(status_code=400, detail="audio file is missing")

        logger.info(
            "utterance in audio: filename={} bytes={} device_id={} session_id={}",
            filename,
            len(payload),
            device_id,
            session_id,
        )

        with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
            tmp.write(payload)
            tmp_path = tmp.name

        try:
            current: AudioTranscriber = request.app.state.transcriber
            transcript = current.transcribe(tmp_path)
        except Exception:
            logger.exception(
                "utterance transcribe failed: filename={} bytes={}",
                filename,
                len(payload),
            )
            raise
        finally:
            Path(tmp_path).unlink(missing_ok=True)

        logger.info("utterance stt: {}", json.dumps({"transcript": transcript}, ensure_ascii=False))
        routed = route(
            transcript,
            store=request.app.state.sessions,
            session_id=session_id,
        )
        request.app.state.sessions.add(session_id, transcript)
        body = {
            "transcript": transcript,
            "reply_text": routed["reply_text"],
            "intent": routed["intent"],
            "actions": routed["actions"],
            "slots": routed.get("slots") or {},
            "missing": routed.get("missing") or [],
            "shutdown": bool(routed.get("shutdown")),
            "device_id": device_id,
            "session_id": session_id,
        }
        logger.info("utterance out: {}", json.dumps(body, ensure_ascii=False))
        return JSONResponse(body)

    return app


app = create_app()
