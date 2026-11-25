from fastapi.testclient import TestClient


def _wav_bytes() -> bytes:
    return (
        b"RIFF$\x00\x00\x00WAVEfmt "
        b"\x10\x00\x00\x00\x01\x00\x01\x00"
        b"\x80>\x00\x00\x00}\x00\x00\x02\x00\x10\x00"
        b"data\x00\x00\x00\x00"
    )


def test_should_return_transcript_when_audio_upload_is_valid(client: TestClient) -> None:
    response = client.post(
        "/v1/utterance",
        files={"audio": ("clip.wav", _wav_bytes(), "audio/wav")},
        data={"device_id": "pixel", "session_id": "s1"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["transcript"] == "привет мир"
    assert body["intent"] == "unknown"
    assert body["actions"] == []
    assert body["slots"] == {}
    assert body["missing"] == []
    assert body["shutdown"] is False
    assert body["reply_text"] == "Принято. Действия пока не подключены."


def test_should_return_400_when_audio_file_is_missing(client: TestClient) -> None:
    response = client.post(
        "/v1/utterance",
        data={"device_id": "pixel", "session_id": "s1"},
    )

    assert response.status_code == 400
    assert response.json()["detail"] == "audio file is missing"
