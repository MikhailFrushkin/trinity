from fastapi.testclient import TestClient


def test_should_return_400_when_audio_payload_is_empty(client: TestClient) -> None:
    response = client.post(
        "/v1/utterance",
        files={"audio": ("clip.wav", b"", "audio/wav")},
    )

    assert response.status_code == 400
    assert response.json()["detail"] == "audio file is missing"
