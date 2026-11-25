from fastapi.testclient import TestClient
import pytest

from app.main import create_app


class FakeTranscriber:
    ready = True

    def load(self) -> None:
        self.ready = True

    def transcribe(self, audio_path: str) -> str:
        assert audio_path
        return "привет мир"


@pytest.fixture
def client() -> TestClient:
    application = create_app(FakeTranscriber())
    with TestClient(application) as test_client:
        yield test_client
