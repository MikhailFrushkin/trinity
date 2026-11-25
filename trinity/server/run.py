import uvicorn
from loguru import logger

from app.config import settings
from app.logging_setup import setup_logging

if __name__ == "__main__":
    setup_logging()
    logger.info("starting trinity server on {}:{}", settings.host, settings.port)
    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        reload=False,
        log_level="info",
        log_config=None,
    )
