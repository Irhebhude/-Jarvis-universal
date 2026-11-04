"""Optional local speech recognition.

If the `vosk` package and a model directory are present, the core transcribes captured PCM
entirely offline. If not, the endpoint answers 501 and the Android app falls back to the platform
recognizer — the fallback is reported to the owner rather than hidden.
"""

from __future__ import annotations

import json
import threading
from pathlib import Path

import wave
import io


class TranscriptionUnavailable(Exception):
    """No local recognition engine/model is installed."""


class LocalTranscriber:
    def __init__(self, model_path: Path | None):
        self.model_path = model_path
        self._model = None
        self._lock = threading.Lock()
        self._import_error: str | None = None
        self._vosk = None
        try:
            import vosk  # type: ignore

            self._vosk = vosk
        except Exception as exc:  # pragma: no cover - depends on device install
            self._import_error = exc.__class__.__name__

    @property
    def available(self) -> bool:
        return (
            self._vosk is not None
            and self.model_path is not None
            and Path(self.model_path).is_dir()
        )

    def status(self) -> dict:
        return {
            "engine": "vosk" if self._vosk is not None else "none",
            "model_path": str(self.model_path) if self.model_path else None,
            "installed": self.available,
            "import_error": self._import_error,
        }

    def _get_model(self):
        if not self.available:
            raise TranscriptionUnavailable(
                "No local speech model. Install vosk and a model directory, or use the device "
                "recognizer (the app falls back automatically)."
            )
        with self._lock:
            if self._model is None:
                self._model = self._vosk.Model(str(self.model_path))
            return self._model

    def transcribe(self, pcm: bytes, sample_rate: int = 16000) -> str:
        model = self._get_model()
        with self._lock:
            recognizer = self._vosk.KaldiRecognizer(model, float(sample_rate))
            recognizer.SetWords(False)
            if recognizer.AcceptWaveform(pcm):
                result = json.loads(recognizer.Result() or "{}")
            else:
                result = json.loads(recognizer.FinalResult() or "{}")
        return (result.get("text") or "").strip()

    def transcribe_wav(self, wav_bytes: bytes) -> str:
        with wave.open(io.BytesIO(wav_bytes), "rb") as handle:
            if handle.getnchannels() != 1 or handle.getsampwidth() != 2:
                raise ValueError("expected 16-bit mono WAV")
            return self.transcribe(handle.readframes(handle.getnframes()), handle.getframerate())
