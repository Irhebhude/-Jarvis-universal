"""Grounded visual reasoning for frames the handset captured.

The phone does the perception: CameraX frames go through ML Kit on-device and come here as
structured facts (faces, objects, recognised text, barcodes). This module turns that evidence —
plus the frame itself, but only when the configured model actually accepts images — into a short
spoken answer.

Two hard rules:

* The existing primary provider is never replaced. If its model is text-only, the adapter simply
  does not send the image and answers from local evidence instead.
* Nothing is invented. Every claim in a local answer is traceable to a fact the phone measured.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any

from .config import Settings
from .providers import Provider, ProviderError, ProviderUnavailable

MAX_ANSWER_CHARS = 700
MAX_EVIDENCE_OBJECTS = 8
MAX_TEXT_CHARS = 300

VISION_PROMPT = """You are JARVIS, looking through the owner's {camera} camera on their phone.

Answer the owner's question using only what is visible in this frame plus the on-device analysis
below. Be concrete and brief: at most three spoken sentences, no markdown, no lists, no emoji.
If the frame is too blurry or dark to answer, say exactly that instead of guessing. Never describe
a person's identity, age, ethnicity, health or emotions beyond what is plainly visible, and never
claim to recognise who someone is.

On-device analysis:
{evidence}

Owner's question: {question}
"""


def _clean(value: Any, limit: int) -> str:
    text = re.sub(r"\s+", " ", str(value or "")).strip()
    return text[:limit]


@dataclass
class VisionFacts:
    """Structured perception results reported by the handset."""

    camera: str = "rear"
    faces: int = 0
    user_present: bool = False
    facing_camera: str = "unknown"
    head_yaw: float | None = None
    objects: list[dict[str, Any]] = field(default_factory=list)
    text: str = ""
    codes: list[str] = field(default_factory=list)
    scene: str = ""
    width: int = 0
    height: int = 0
    frame_age_ms: int = 0

    @classmethod
    def parse(cls, raw: dict[str, Any] | None, camera: str = "rear") -> "VisionFacts":
        raw = raw if isinstance(raw, dict) else {}
        objects: list[dict[str, Any]] = []
        for item in raw.get("objects") or []:
            if not isinstance(item, dict):
                continue
            label = _clean(item.get("label"), 60)
            if not label:
                continue
            try:
                confidence = float(item.get("confidence") or 0.0)
            except (TypeError, ValueError):
                confidence = 0.0
            objects.append({"label": label, "confidence": max(0.0, min(1.0, confidence))})
            if len(objects) >= 25:
                break
        codes = [_clean(c, 120) for c in (raw.get("codes") or []) if _clean(c, 120)]
        try:
            faces = int(raw.get("faces") or 0)
        except (TypeError, ValueError):
            faces = 0
        yaw_raw = raw.get("head_yaw")
        try:
            head_yaw = float(yaw_raw) if yaw_raw is not None else None
        except (TypeError, ValueError):
            head_yaw = None
        facing = _clean(raw.get("facing_camera"), 20).lower() or "unknown"
        if facing not in ("yes", "no", "unknown"):
            facing = "unknown"
        return cls(
            camera=_clean(camera or raw.get("camera") or "rear", 12).lower() or "rear",
            faces=max(0, faces),
            user_present=bool(raw.get("user_present")) or faces > 0,
            facing_camera=facing,
            head_yaw=head_yaw,
            objects=objects,
            text=_clean(raw.get("text"), MAX_TEXT_CHARS),
            codes=codes[:6],
            scene=_clean(raw.get("scene"), 120),
            width=int(raw.get("width") or 0),
            height=int(raw.get("height") or 0),
            frame_age_ms=int(raw.get("frame_age_ms") or 0),
        )

    # ------------------------------------------------------------------ evidence

    def top_objects(self) -> list[str]:
        seen: list[str] = []
        for item in sorted(self.objects, key=lambda o: o["confidence"], reverse=True):
            label = item["label"]
            if label.lower() in (s.lower() for s in seen):
                continue
            seen.append(label)
            if len(seen) >= MAX_EVIDENCE_OBJECTS:
                break
        return seen

    def evidence_lines(self) -> list[str]:
        lines: list[str] = [f"camera: {self.camera}"]
        if self.faces:
            lines.append(f"people visible: {self.faces}")
            if self.facing_camera != "unknown":
                lines.append(f"facing the camera: {self.facing_camera}")
            if self.head_yaw is not None:
                lines.append(f"head yaw: {self.head_yaw:.0f} degrees")
        else:
            lines.append("no people detected")
        if self.objects:
            labels = ", ".join(
                f"{o['label']} ({o['confidence']:.0%})" for o in self.objects[:MAX_EVIDENCE_OBJECTS]
            )
            lines.append(f"detected objects: {labels}")
        if self.text:
            lines.append(f"recognised text: {self.text}")
        if self.codes:
            lines.append("codes: " + "; ".join(self.codes))
        if self.scene:
            lines.append(f"scene: {self.scene}")
        return lines

    def has_evidence(self) -> bool:
        return bool(self.faces or self.objects or self.text or self.codes or self.scene)

    # ------------------------------------------------------------------ local answer

    def grounded_answer(self, question: str = "") -> str:
        """Composes a spoken answer using only measured facts. No provider involved."""
        parts: list[str] = []
        if self.faces:
            who = "one person is" if self.faces == 1 else f"{self.faces} people are"
            if self.facing_camera == "yes":
                parts.append(f"{who} in view and facing the camera.")
            elif self.facing_camera == "no":
                parts.append(f"{who} in view, but looking away from the camera.")
            else:
                parts.append(f"{who} in view.")
        elif self.camera == "front":
            parts.append("I don't see anyone in front of the camera right now.")

        objects = self.top_objects()
        if objects:
            if len(objects) == 1:
                parts.append(f"I can see {objects[0]}.")
            else:
                parts.append("I can see " + ", ".join(objects[:-1]) + f" and {objects[-1]}.")
        if self.text:
            spoken_text = self.text if len(self.text) <= 220 else self.text[:217] + "..."
            parts.append(f'The text reads: "{spoken_text}".')
        if self.codes:
            parts.append("There is a code containing " + _clean(self.codes[0], 60) + ".")
        if self.scene:
            parts.append(self.scene.rstrip(".") + ".")

        if not parts:
            return (
                "I can't make anything out clearly in this frame. Try steadying the phone and "
                "pointing it at what you want me to look at."
            )
        if question and self.faces and "who" in question.lower():
            parts.append("I can tell someone is there, but I don't identify people from the camera.")
        return " ".join(parts)[:MAX_ANSWER_CHARS]


@dataclass
class VisionAnswer:
    answer: str
    source: str            # "provider" | "local-facts"
    model: str = ""
    camera: str = "rear"
    detail: str = ""

    def json(self) -> dict[str, Any]:
        return {
            "answer": self.answer,
            "source": self.source,
            "model": self.model,
            "camera": self.camera,
            "detail": self.detail,
        }


class VisionEngine:
    """Chooses between the provider's vision path and a grounded local answer."""

    def __init__(self, provider: Provider, settings: Settings):
        self.provider = provider
        self.settings = settings

    @property
    def remote_available(self) -> bool:
        return bool(getattr(self.settings, "vision_enabled", True)) and bool(
            getattr(self.provider, "supports_vision", False)
        )

    @property
    def vision_model(self) -> str:
        configured = getattr(self.settings, "vision_model", "") or ""
        return configured or getattr(self.provider, "vision_model", "") or self.provider.model

    def analyze(
        self,
        image_b64: str,
        mime: str,
        question: str,
        facts: VisionFacts,
    ) -> VisionAnswer:
        question = _clean(question, 300) or "What am I looking at?"

        if image_b64 and self.remote_available:
            prompt = VISION_PROMPT.format(
                camera=facts.camera,
                evidence="\n".join(f"- {line}" for line in facts.evidence_lines()),
                question=question,
            )
            try:
                text = _clean(
                    self.provider.complete_vision(image_b64, mime or "image/jpeg", prompt),
                    MAX_ANSWER_CHARS,
                )
                if text:
                    return VisionAnswer(
                        answer=text,
                        source="provider",
                        model=self.vision_model,
                        camera=facts.camera,
                    )
                raise ProviderError("empty vision response")
            except (ProviderUnavailable, ProviderError) as exc:
                local = facts.grounded_answer(question)
                return VisionAnswer(
                    answer=(
                        f"{local} I could not ask {self.vision_model or 'the AI model'} about the "
                        "image right now."
                    )[:MAX_ANSWER_CHARS],
                    source="local-facts",
                    model=self.vision_model,
                    camera=facts.camera,
                    detail=str(exc)[:200],
                )

        local = facts.grounded_answer(question)
        if self.remote_available:
            return VisionAnswer(answer=local, source="local-facts",
                                model=self.vision_model, camera=facts.camera,
                                detail="no frame supplied")
        return VisionAnswer(
            answer=local,
            source="local-facts",
            model="",
            camera=facts.camera,
            detail=(
                "no image-capable model configured; answering from on-device detection only"
            ),
        )
