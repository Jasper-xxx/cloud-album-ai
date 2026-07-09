"""Development entry point for the FastAPI AI service."""

import uvicorn

from app.core.config import get_settings

if __name__ == "__main__":
    settings = get_settings()
    uvicorn.run(
        "app.main:app",
        host=settings.ai_service_host,
        port=settings.ai_service_port,
        reload=settings.ai_service_debug,
        workers=1,
    )
