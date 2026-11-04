from __future__ import annotations

import base64

import pytest
from fastapi.testclient import TestClient

from jarvis_core.config import Settings
from jarvis_core.providers import ProviderError, model_accepts_images
from jarvis_core.server import create_app
from jarvis_core.vision import VisionEngine, VisionFacts


def _jpeg_b64(size: int = 512) -> str:
    return base64.b64encode(b"\xff\xd8\xff" + b"\x00" * size).decode()


class TextOnlyProvider:
    name = "stub-text"
    model = "some-text-model"
    vision_model = "some-text-model"
    supports_vision = False

    def complete(self, messages, temperature: float = 0.3) -> str:
        return "text only"

    def complete_vision(self, image_b64, mime, prompt, temperature: float = 0.2) -> str:
        raise AssertionError("text-only provider must never be asked for an image")


class VisionProvider:
    name = "stub-vision"
    model = "gpt-4o-mini"
    vision_model = "gpt-4o-mini"
    supports_vision = True

    def __init__(self, reply: str = "A laptop sits on a wooden desk."):
        self.reply = reply
        self.calls: list[tuple[str, str]] = []

    def complete(self, messages, temperature: float = 0.3) -> str:
        return self.reply

    def complete_vision(self, image_b64, mime, prompt, temperature: float = 0.2) -> str:
        self.calls.append((mime, prompt))
        return self.reply


class BrokenVisionProvider(VisionProvider):
    supports_vision = True

    def complete_vision(self, image_b64, mime, prompt, temperature: float = 0.2) -> str:
        raise ProviderError("provider returned HTTP 400")


# ---------------------------------------------------------------- capability checks


def test_vision_capability_is_inspected_not_assumed():
    assert model_accepts_images("gpt-4o-mini") is True
    assert model_accepts_images("gemini-1.5-flash") is True
    assert model_accepts_images("claude-3-5-haiku-latest") is True
    assert model_accepts_images("llama-3.1-8b-instant") is False
    assert model_accepts_images("mixtral-8x7b") is False
    assert model_accepts_images("") is False


def test_engine_reports_remote_unavailable_for_text_only_model(settings: Settings):
    engine = VisionEngine(TextOnlyProvider(), settings)
    assert engine.remote_available is False


# ---------------------------------------------------------------- fact grounding


def test_facts_parse_and_grounded_answer_uses_measurements():
    facts = VisionFacts.parse(
        {
            "faces": 1,
            "user_present": True,
            "facing_camera": "yes",
            "objects": [
                {"label": "laptop", "confidence": 0.91},
                {"label": "cup", "confidence": 0.62},
            ],
            "text": "Invoice 4471",
            "codes": ["https://example.com/x"],
        },
        camera="rear",
    )
    answer = facts.grounded_answer("what do you see")
    assert "one person is in view and facing the camera" in answer
    assert "laptop" in answer and "cup" in answer
    assert "Invoice 4471" in answer
    assert "example.com/x" in answer


def test_empty_frame_is_described_honestly():
    facts = VisionFacts.parse({}, camera="front")
    answer = facts.grounded_answer()
    assert "don't see anyone" in answer or "can't make anything out" in answer


def test_identity_is_never_claimed():
    facts = VisionFacts.parse({"faces": 1, "facing_camera": "yes"})
    answer = facts.grounded_answer("who is this")
    assert "don't identify people" in answer
    assert "name" not in answer.lower()


def test_looking_away_is_distinguished_from_facing():
    facing = VisionFacts.parse({"faces": 1, "facing_camera": "yes"}).grounded_answer()
    away = VisionFacts.parse({"faces": 1, "facing_camera": "no"}).grounded_answer()
    assert facing != away
    assert "looking away" in away


# ---------------------------------------------------------------- engine decisions


def test_text_only_engine_answers_from_facts_without_sending_image(settings: Settings):
    engine = VisionEngine(TextOnlyProvider(), settings)
    facts = VisionFacts.parse({"objects": [{"label": "chair", "confidence": 0.8}]})
    answer = engine.analyze(_jpeg_b64(), "image/jpeg", "what is this", facts)
    assert answer.source == "local-facts"
    assert "chair" in answer.answer
    assert "image-capable" in answer.detail


def test_vision_provider_receives_frame_and_evidence(settings: Settings):
    provider = VisionProvider()
    engine = VisionEngine(provider, settings)
    facts = VisionFacts.parse({"objects": [{"label": "laptop", "confidence": 0.9}]})
    answer = engine.analyze(_jpeg_b64(), "image/jpeg", "what am i looking at", facts)
    assert answer.source == "provider"
    assert answer.answer == "A laptop sits on a wooden desk."
    assert len(provider.calls) == 1
    mime, prompt = provider.calls[0]
    assert mime == "image/jpeg"
    assert "laptop" in prompt
    assert "what am i looking at" in prompt


def test_provider_failure_falls_back_to_evidence(settings: Settings):
    engine = VisionEngine(BrokenVisionProvider(), settings)
    facts = VisionFacts.parse({"objects": [{"label": "bottle", "confidence": 0.77}]})
    answer = engine.analyze(_jpeg_b64(), "image/jpeg", "what is this", facts)
    assert answer.source == "local-facts"
    assert "bottle" in answer.answer
    assert "could not ask" in answer.answer


def test_missing_frame_never_calls_the_provider(settings: Settings):
    provider = VisionProvider()
    engine = VisionEngine(provider, settings)
    answer = engine.analyze("", "image/jpeg", "what is this", VisionFacts.parse({}))
    assert provider.calls == []
    assert answer.source == "local-facts"


# ---------------------------------------------------------------- endpoint


@pytest.fixture()
def client(settings: Settings):
    app = create_app(settings)
    with TestClient(app) as test_client:
        test_client.app_state = app.state
        yield test_client


def _pair(client: TestClient) -> str:
    code, _ = client.app_state.pairing.issue_code()
    response = client.post("/pair", json={"code": code, "device_name": "camera-test"})
    assert response.status_code == 200, response.text
    return response.json()["device_token"]


def test_vision_endpoint_requires_auth(client: TestClient):
    response = client.post("/vision/analyze", json={"question": "what is this"})
    assert response.status_code == 401


def test_vision_endpoint_rejects_oversized_frames(client: TestClient):
    token = _pair(client)
    huge = "A" * 4_100_000
    response = client.post(
        "/vision/analyze",
        headers={"Authorization": f"Bearer {token}"},
        json={"image_b64": huge, "question": "what is this"},
    )
    assert response.status_code == 413


def test_vision_endpoint_rejects_invalid_base64(client: TestClient):
    token = _pair(client)
    response = client.post(
        "/vision/analyze",
        headers={"Authorization": f"Bearer {token}"},
        json={"image_b64": "not!base64!", "question": "what is this"},
    )
    assert response.status_code == 400


def test_vision_endpoint_answers_from_facts_without_a_provider(client: TestClient):
    token = _pair(client)
    response = client.post(
        "/vision/analyze",
        headers={"Authorization": f"Bearer {token}"},
        json={
            "image_b64": _jpeg_b64(),
            "question": "what do you see",
            "camera": "rear",
            "facts": {
                "faces": 0,
                "objects": [{"label": "monitor", "confidence": 0.88}],
                "text": "STOP",
            },
        },
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["source"] == "local-facts"
    assert "monitor" in body["answer"]
    assert body["remote"] is False
    assert body["facts"]["objects"] == 1


def test_vision_status_is_visible_in_integrations(client: TestClient):
    token = _pair(client)
    response = client.get("/integrations/status", headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 200
    assert "vision" in response.json()
    assert response.json()["vision"]["remote_analysis"] is False


# ---------------------------------------------------------------- voice routing


def test_camera_phrases_route_to_registered_tools(settings, db, memory, stub_provider):
    from jarvis_core.planner import Planner
    from jarvis_core.tools import TOOLS

    planner = Planner(settings, db, memory, stub_provider("{}"))

    cases = {
        "hey jarvis activate the rear camera": "camera_start",
        "jarvis activate the front camera": "camera_start",
        "jarvis switch camera": "camera_start",
        "jarvis stop camera": "camera_stop",
        "jarvis what am i looking at": "camera_describe",
        "jarvis what do you see": "camera_describe",
        "jarvis read this": "camera_read_text",
        "jarvis identify this": "camera_identify",
        "jarvis what am i holding": "camera_identify",
        "jarvis can you see me": "camera_describe",
    }

    for phrase, expected in cases.items():
        plan = planner.plan(phrase, "camera-session")
        assert plan.actions, f"{phrase!r} produced no plan"
        ids = [a.id for a in plan.actions]
        assert expected in ids, f"{phrase!r} planned {ids}, expected {expected}"
        for tool_id in ids:
            assert tool_id in TOOLS, f"planner invented {tool_id}"


def test_camera_modes_are_mapped_exactly(settings, db, memory, stub_provider):
    from jarvis_core.planner import Planner

    planner = Planner(settings, db, memory, stub_provider("{}"))
    assert planner.plan("jarvis activate the rear camera", "s").actions[0].args == {"mode": "rear"}
    assert planner.plan("jarvis activate the front camera", "s").actions[0].args == {"mode": "front"}
    assert planner.plan("jarvis switch camera", "s").actions[0].args == {"mode": "switch"}
    assert planner.plan("jarvis can you see me", "s").actions[0].args["camera"] == "front"


def test_camera_plans_never_require_confirmation(settings, db, memory, stub_provider):
    from jarvis_core.planner import Planner

    planner = Planner(settings, db, memory, stub_provider("{}"))
    for phrase in ("jarvis activate the rear camera", "jarvis stop camera", "jarvis read this"):
        plan = planner.plan(phrase, "s")
        assert all(not a.needs_confirmation for a in plan.actions), phrase
