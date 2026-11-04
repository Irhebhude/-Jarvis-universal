"""AI provider adapters.

The project is deliberately provider agnostic: nothing here assumes Groq, OpenRouter, Gemini,
OpenAI or Claude. Whichever key the owner exports is used, and the exact provider and model are
reported over /status so the app can show them.

Only the owner's key is used, it is read from the environment and never logged.
"""

from __future__ import annotations

import json
from typing import Any

import httpx

from .config import Settings


class ProviderError(Exception):
    """The provider answered but something went wrong (rate limit, bad model, ...)."""


class ProviderUnavailable(ProviderError):
    """No provider is configured, or it could not be reached."""


class Provider:
    name = "offline"
    model = ""

    def complete(self, messages: list[dict[str, str]], temperature: float = 0.3) -> str:
        raise ProviderUnavailable(
            "No AI provider is configured. Export an API key before starting the core, "
            "for example: export OPENAI_API_KEY=... (or GEMINI_API_KEY / ANTHROPIC_API_KEY). "
            "Rule-based device commands still work without it."
        )


class OpenAICompatibleProvider(Provider):
    name = "openai-compatible"

    def __init__(self, base_url: str, api_key: str, model: str, timeout: float = 45.0):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.timeout = timeout

    def complete(self, messages: list[dict[str, str]], temperature: float = 0.3) -> str:
        if not self.api_key:
            raise ProviderUnavailable("Provider key is missing from the environment.")
        payload = {
            "model": self.model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": 700,
        }
        headers = {"Authorization": f"Bearer {self.api_key}", "Content-Type": "application/json"}
        try:
            with httpx.Client(timeout=self.timeout) as client:
                response = client.post(
                    f"{self.base_url}/chat/completions", headers=headers, json=payload
                )
        except httpx.HTTPError as exc:
            raise ProviderUnavailable(f"AI provider unreachable: {exc.__class__.__name__}") from exc

        if response.status_code >= 400:
            raise ProviderError(
                f"AI provider returned HTTP {response.status_code}: {response.text[:160]}"
            )
        try:
            data = response.json()
            return data["choices"][0]["message"]["content"] or ""
        except (KeyError, ValueError, IndexError) as exc:
            raise ProviderError("AI provider returned an unexpected response shape.") from exc


class GeminiProvider(Provider):
    name = "gemini"

    def __init__(self, base_url: str, api_key: str, model: str, timeout: float = 45.0):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.timeout = timeout

    def complete(self, messages: list[dict[str, str]], temperature: float = 0.3) -> str:
        system = "\n".join(m["content"] for m in messages if m.get("role") == "system")
        contents = [
            {
                "role": "model" if m.get("role") == "assistant" else "user",
                "parts": [{"text": m.get("content", "")}],
            }
            for m in messages
            if m.get("role") != "system"
        ]
        payload: dict[str, Any] = {
            "contents": contents,
            "generationConfig": {"temperature": temperature, "maxOutputTokens": 700},
        }
        if system:
            payload["systemInstruction"] = {"parts": [{"text": system}]}
        url = f"{self.base_url}/models/{self.model}:generateContent"
        try:
            with httpx.Client(timeout=self.timeout) as client:
                response = client.post(
                    url,
                    params={"key": self.api_key},
                    json=payload,
                    headers={"Content-Type": "application/json"},
                )
        except httpx.HTTPError as exc:
            raise ProviderUnavailable(f"Gemini unreachable: {exc.__class__.__name__}") from exc

        if response.status_code >= 400:
            raise ProviderError(
                f"Gemini returned HTTP {response.status_code}: {response.text[:160]}"
            )
        try:
            data = response.json()
            return data["candidates"][0]["content"]["parts"][0]["text"] or ""
        except (KeyError, ValueError, IndexError) as exc:
            raise ProviderError("Gemini returned an unexpected response shape.") from exc


class AnthropicProvider(Provider):
    name = "anthropic"

    def __init__(self, base_url: str, api_key: str, model: str, timeout: float = 45.0):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.timeout = timeout

    def complete(self, messages: list[dict[str, str]], temperature: float = 0.3) -> str:
        system = "\n".join(m["content"] for m in messages if m.get("role") == "system")
        convo = [
            {"role": m["role"], "content": m.get("content", "")}
            for m in messages
            if m.get("role") in ("user", "assistant")
        ]
        payload = {
            "model": self.model,
            "max_tokens": 700,
            "temperature": temperature,
            "messages": convo,
        }
        if system:
            payload["system"] = system
        try:
            with httpx.Client(timeout=self.timeout) as client:
                response = client.post(
                    f"{self.base_url}/messages",
                    headers={
                        "x-api-key": self.api_key,
                        "anthropic-version": "2023-06-01",
                        "Content-Type": "application/json",
                    },
                    json=payload,
                )
        except httpx.HTTPError as exc:
            raise ProviderUnavailable(f"Claude unreachable: {exc.__class__.__name__}") from exc

        if response.status_code >= 400:
            raise ProviderError(
                f"Claude returned HTTP {response.status_code}: {response.text[:160]}"
            )
        try:
            data = response.json()
            blocks = data.get("content", [])
            return "".join(b.get("text", "") for b in blocks if b.get("type") == "text")
        except (ValueError, TypeError) as exc:
            raise ProviderError("Claude returned an unexpected response shape.") from exc


class OfflineProvider(Provider):
    name = "offline"
    model = ""

    def complete(self, messages: list[dict[str, str]], temperature: float = 0.3) -> str:
        raise ProviderUnavailable(
            "No AI provider is configured, so I can only use rule-based device commands. "
            "Export a provider key and restart the core for free-form conversation."
        )


def build_provider(settings: Settings) -> Provider:
    provider = settings.provider.lower()
    if provider == "gemini":
        return GeminiProvider(settings.base_url, settings.api_key, settings.model,
                              settings.request_timeout_seconds)
    if provider == "anthropic":
        return AnthropicProvider(settings.base_url, settings.api_key, settings.model,
                                 settings.request_timeout_seconds)
    if provider in ("openai", "openai-compatible", "groq", "openrouter", "ollama", "local"):
        return OpenAICompatibleProvider(settings.base_url, settings.api_key, settings.model,
                                        settings.request_timeout_seconds)
    return OfflineProvider()


def extract_json_object(text: str) -> dict[str, Any] | None:
    """Parses the first JSON object in a model response, tolerating code fences."""
    if not text:
        return None
    cleaned = text.strip()
    if cleaned.startswith("```"):
        cleaned = cleaned.strip("`")
        if cleaned.lower().startswith("json"):
            cleaned = cleaned[4:]
    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        parsed = json.loads(cleaned[start:end + 1])
        return parsed if isinstance(parsed, dict) else None
    except json.JSONDecodeError:
        return None
