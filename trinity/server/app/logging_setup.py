from __future__ import annotations

import logging
import sys
from pathlib import Path

from loguru import logger

LOG_FORMAT = "{time:YYYY-MM-DD HH:mm:ss.SSS} | {level:<8} | {name}:{function}:{line} | {message}"
LOG_DIR = Path(__file__).resolve().parent.parent / "logs"
LOG_FILE = LOG_DIR / "trinity.log"


class InterceptHandler(logging.Handler):
    def emit(self, record: logging.LogRecord) -> None:
        try:
            level: str | int = logger.level(record.levelname).name
        except ValueError:
            level = record.levelno
        frame, depth = logging.currentframe(), 2
        while frame is not None and frame.f_code.co_filename == logging.__file__:
            frame = frame.f_back
            depth += 1
        logger.opt(depth=depth, exception=record.exc_info).log(level, record.getMessage())


def setup_logging() -> None:
    LOG_DIR.mkdir(parents=True, exist_ok=True)
    logger.remove()
    logger.add(sys.stderr, format=LOG_FORMAT, level="INFO", enqueue=True)
    logger.add(
        LOG_FILE,
        format=LOG_FORMAT,
        level="INFO",
        rotation="10 MB",
        retention="7 days",
        encoding="utf-8",
        enqueue=True,
    )
    logging.basicConfig(handlers=[InterceptHandler()], level=logging.INFO, force=True)
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        intercept = logging.getLogger(name)
        intercept.handlers = [InterceptHandler()]
        intercept.propagate = False
