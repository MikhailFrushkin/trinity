from typing import Protocol

WHISPER_PROMPT = (
    "Складские голосовые команды на русском. "
    "Номер заказа всегда шесть цифр подряд без дефисов и пробелов, например 163284. "
    "Зоны сборки: магазин, склад, СС. "
    "Команды: размести заказ, распечатай стикер."
)


class AudioTranscriber(Protocol):
    ready: bool

    def load(self) -> None: ...

    def transcribe(self, audio_path: str) -> str: ...


class WhisperTranscriber:
    def __init__(self, model_size: str = "small") -> None:
        self._model_size = model_size
        self._model = None
        self.ready = False

    def load(self) -> None:
        from faster_whisper import WhisperModel

        self._model = WhisperModel(
            self._model_size,
            device="cpu",
            compute_type="int8",
        )
        self.ready = True

    def transcribe(self, audio_path: str) -> str:
        if self._model is None:
            raise RuntimeError("whisper model is not loaded")
        segments, _info = self._model.transcribe(
            audio_path,
            language="ru",
            vad_filter=True,
            condition_on_previous_text=False,
            beam_size=5,
            initial_prompt=WHISPER_PROMPT,
        )
        return " ".join(segment.text.strip() for segment in segments).strip()
