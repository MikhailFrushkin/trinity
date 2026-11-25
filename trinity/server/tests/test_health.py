from fastapi.testclient import TestClient


def test_should_report_ready_when_transcriber_is_loaded(client: TestClient) -> None:
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"ok": True, "model_ready": True}
