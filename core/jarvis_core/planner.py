"""Intent classification, planning and response composition.

Two lanes:
  1. Deterministic rules for the common device requests. These work with no AI provider at all,
     are fast, and are what makes the assistant usable entirely offline.
  2. The owner's configured AI provider for everything else, constrained to emit JSON that names
     registered tools only.

The planner never executes anything. It returns a plan; the Android device executes it and
reports structured results; only then is a completion sentence composed. That is what keeps
JARVIS from claiming something happened when it did not.
"""

from __future__ import annotations

import json
import re
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

from .config import Settings
from .db import Database
from .memory import MemoryStore, find_secret
from .providers import Provider, ProviderError, ProviderUnavailable, extract_json_object
from .tools import TOOLS, compact_prompt, needs_confirmation, validate_call

DANGEROUS_PATTERNS = [
    (re.compile(r"\brm\s+-rf\b", re.I), "destructive file deletion"),
    (re.compile(r"\bsudo\b", re.I), "privileged shell access"),
    (re.compile(r"\b(?:curl|wget)\b[^\n]*\|\s*(?:sh|bash)\b", re.I), "running a remote script"),
    (re.compile(r"\badb\s+shell\b", re.I), "adb shell access"),
    (re.compile(r"\bsu\s+-c\b", re.I), "root shell access"),
    (re.compile(r"\b(?:transfer|wire|send)\s+(?:money|cash|funds|payment)\b", re.I), "money transfer"),
    (re.compile(r"\b(?:bitcoin|ethereum|crypto)\b[^\n]*\b(?:send|transfer)\b", re.I), "crypto transfer"),
    (re.compile(r"\b(?:disable|turn\s+off)\s+(?:screen\s*lock|lock\s*screen|device\s+security)\b", re.I),
     "disabling device security"),
    (re.compile(r"\b(?:install|sideload)\s+(?:an?\s+)?apk\b", re.I), "installing an APK"),
]

SYSTEM_PROMPT = """You are JARVIS, a calm, precise female assistant living on the owner's Android phone.

Rules you must follow:
1. Use ONLY the tools listed below. Never invent a tool, permission or capability.
2. Reply with exactly one JSON object and nothing else:
   {{"say": "short spoken reply", "plan": [{{"tool": "tool_id", "arguments": {{}}}}], "remember": [{{"kind": "preference|fact|project|task|device|contact_preference|note", "content": "..."}}]}}
3. If no device action is needed, return an empty plan and put the answer in "say".
4. Never claim an action succeeded. The device executes tools and reports results; you will then be asked to summarise them. Until then speak in the future or present tense.
5. Never ask for or repeat passwords, tokens, API keys or one-time codes.
6. You cannot run shell commands, root the device, install packages, make payments or bypass Android security. If asked, explain the limitation in "say" with an empty plan.
7. Keep "say" under 45 words, spoken aloud, no markdown, no lists, no emoji.
8. Only use "remember" for durable owner preferences or facts, never for secrets.

AVAILABLE TOOLS
{tools}
"""


@dataclass
class PlannedAction:
    id: str
    args: dict[str, Any]
    needs_confirmation: bool
    description: str

    def json(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "args": self.args,
            "needs_confirmation": self.needs_confirmation,
            "description": self.description,
        }


@dataclass
class Plan:
    plan_id: str = ""
    speech: str = ""
    actions: list[PlannedAction] = field(default_factory=list)
    context: list[str] = field(default_factory=list)
    error: str | None = None

    def json(self) -> dict[str, Any]:
        out = {
            "plan_id": self.plan_id,
            "speech": self.speech,
            "actions": [a.json() for a in self.actions],
            "context": self.context,
        }
        if self.error:
            out["error"] = self.error
        return out


def _action(tool_id: str, **args: Any) -> PlannedAction:
    tool = TOOLS[tool_id]
    return PlannedAction(
        id=tool_id,
        args=args,
        needs_confirmation=needs_confirmation(tool_id),
        description=tool.description,
    )


class Planner:
    def __init__(self, settings: Settings, db: Database, memory: MemoryStore, provider: Provider):
        self.settings = settings
        self.db = db
        self.memory = memory
        self.provider = provider

    # ------------------------------------------------------------------ public

    def plan(self, command: str, session: str, device_context: dict[str, Any] | None = None) -> Plan:
        command = (command or "").strip()
        if not command:
            return Plan(speech="I did not catch that.")
        if len(command) > self.settings.max_command_chars:
            return Plan(speech="That command is too long for me to handle safely.")

        your_device_actions = {
            str(a) for a in ((device_context or {}).get("actions") or [])
        }

        for pattern, label in DANGEROUS_PATTERNS:
            if pattern.search(command):
                self.db.audit("assistant", "refused_dangerous", f"{label}: {command[:80]}", False)
                return Plan(
                    speech=(
                        f"I will not do that. {label.capitalize()} is outside what I am allowed "
                        "to do, and I have no shell, root or payment access."
                    ),
                    error="refused_dangerous_request",
                )

        rule_plan = self._rule_plan(command)
        if rule_plan is not None:
            self.memory.add_turn(session, "user", command)
            if rule_plan.actions or rule_plan.speech:
                self.memory.add_turn(session, "assistant", rule_plan.speech or "[planned]")
            return self._finalize(rule_plan, command, session)

        llm_plan = self._llm_plan(command, session, your_device_actions)
        self.memory.add_turn(session, "user", command)
        if llm_plan.speech:
            self.memory.add_turn(session, "assistant", llm_plan.speech)
        return self._finalize(llm_plan, command, session)

    def compose(
        self, plan_id: str, results: list[dict[str, Any]], session: str | None = None
    ) -> dict[str, Any]:
        row = self.db.query_one("SELECT * FROM plans WHERE id = ?", (plan_id,))
        command = row["command"] if row else ""
        session = session or (row["session"] if row else "default")
        fallback = self._deterministic_summary(results)

        speech = fallback
        if self.settings.configured and results:
            try:
                speech = self._llm_summary(command, results) or fallback
            except (ProviderError, ProviderUnavailable):
                speech = fallback

        if row is not None:
            self.db.execute(
                "UPDATE plans SET status = ?, completed_at = ? WHERE id = ?",
                ("completed", time.time(), plan_id),
            )
        if speech:
            self.memory.add_turn(session, "assistant", speech)
        return {
            "reply": "completed" if any(
                (r.get("result") or {}).get("ok") for r in results
            ) else "not_completed",
            "speech": speech,
        }

    # ------------------------------------------------------------------ helpers

    def _finalize(self, plan: Plan, command: str, session: str) -> Plan:
        if not plan.actions:
            return plan
        plan.plan_id = uuid.uuid4().hex
        self.db.execute(
            "INSERT INTO plans(id, session, command, actions, status, created_at) "
            "VALUES(?,?,?,?,?,?)",
            (
                plan.plan_id,
                session,
                command[:400],
                json.dumps([a.json() for a in plan.actions]),
                "planned",
                time.time(),
            ),
        )
        self.db.audit(
            "assistant",
            "plan_created",
            ",".join(a.id for a in plan.actions),
            True,
        )
        return plan

    @staticmethod
    def _deterministic_summary(results: list[dict[str, Any]]) -> str:
        spoken: list[str] = []
        any_ok = False
        for item in results:
            result = item.get("result") or {}
            text = (result.get("spoken") or "").strip()
            if result.get("ok"):
                any_ok = True
            if text:
                spoken.append(text)
        if spoken:
            return " ".join(spoken)
        return "Done." if any_ok else "I could not complete that."

    def _memory_context(self) -> tuple[str, list[str]]:
        memories = self.memory.list(limit=20)
        if not memories:
            return "", []
        lines = [f"- ({m['kind']}) {m['content']}" for m in memories]
        return "\n".join(lines), [m["content"][:120] for m in memories[:6]]

    # ------------------------------------------------------------- rule lane

    def _rule_plan(self, command: str) -> Plan | None:
        # Keep the original casing for anything that will be sent verbatim (message bodies, app
        # names, contact names) and use the lowercased form only for intent matching.
        original = re.sub(r"^(?:hey\s+)?jarvis[,!.\s]+", "", command.strip(), flags=re.I).strip()
        original = re.sub(r"^please\s+", "", original, flags=re.I)
        text = original.lower().strip()

        def spoken(say: str, *actions: PlannedAction) -> Plan:
            return Plan(speech=say, actions=list(actions))

        # --- explicit refusals about capabilities
        if re.search(r"\b(?:shell|terminal|root|sudo|adb)\b", text):
            return spoken(
                "I cannot run shell commands or gain root. Every device operation I perform goes "
                "through a registered, permission-checked action."
            )

        # --- mute / wake control
        if re.search(r"\b(?:mute|stop listening|stop the mic|microphone off)\b", text):
            return spoken("Muting the microphone.", _action("set_microphone_muted", muted=True))
        if re.search(r"\b(?:unmute|resume listening|microphone on)\b", text):
            return spoken("Resuming the microphone.", _action("set_microphone_muted", muted=False))
        if re.search(r"\b(?:disable|turn off|stop)\b.*\bwake\s*word\b", text):
            return spoken("Turning the wake word off.", _action("set_wake_word_enabled", enabled=False))
        if re.search(r"\b(?:enable|turn on|start)\b.*\bwake\s*word\b", text):
            return spoken("Turning the wake word on.", _action("set_wake_word_enabled", enabled=True))

        # --- camera vision (the phone owns the hardware; these plans only steer it)
        if re.search(r"\b(?:stop|close|turn off|deactivate|shut)\b[^.]*\bcamera\b", text) or re.fullmatch(
            r"(?:stop|close) (?:the )?camera", text
        ):
            return spoken("Turning the camera off.", _action("camera_stop"))
        if re.search(r"\b(?:switch|swap|flip|other)\b[^.]*\bcamera\b", text) or re.search(
            r"\bcamera\b[^.]*\b(?:switch|swap|flip)\b", text
        ):
            return spoken("Switching to the other camera.", _action("camera_start", mode="switch"))
        if re.search(r"\b(?:can you see|do you see|am i visible|are you watching)\b", text):
            return spoken("Checking what I can see.",
                          _action("camera_describe", camera="front", question=original[:300]))
        if re.search(r"\b(?:read|what does it say|what is written|what's written)\b", text) and re.search(
            r"\b(?:this|that|it|screen|document|page|sign|label|book|letter|receipt)\b", text
        ):
            return spoken("Let me read that.", _action("camera_read_text", camera="current"))
        if re.search(r"\bidentify\b|\bwhat am i holding\b|\bwhat is this\b|\bwhat's this\b", text):
            return spoken("Let me take a look.",
                          _action("camera_identify", camera="current", question=original[:300]))
        if re.search(
            r"\b(?:what do you see|what can you see|what am i looking at|describe (?:my )?surroundings|"
            r"describe what you see|look at this|look around|examine this|inspect this)\b",
            text,
        ):
            return spoken("Looking now.",
                          _action("camera_describe", camera="current", question=original[:300]))
        if re.search(r"\b(?:front|selfie)\b[^.]*\bcamera\b|\bcamera\b[^.]*\bfront\b", text):
            return spoken("Turning on the front camera.", _action("camera_start", mode="front"))
        if re.search(r"\b(?:rear|back)\b[^.]*\bcamera\b|\bcamera\b[^.]*\b(?:rear|back)\b", text):
            return spoken("Turning on the rear camera.", _action("camera_start", mode="rear"))
        if re.search(r"\b(?:start|open|activate|enable|turn on)\b[^.]*\bcamera\b|^camera on$", text):
            return spoken("Turning the rear camera on.", _action("camera_start", mode="rear"))

        # --- status
        if re.search(r"\b(?:what are you doing|your status|how are you|are you listening)\b", text):
            return spoken("Let me check my own state.", _action("jarvis_status"))
        if re.search(r"\bbattery\b", text):
            return spoken("Let me read the battery.", _action("battery_status"))
        if re.search(r"\b(?:network|internet|wifi|wi-fi|online|connection)\b", text):
            if re.search(r"\b(?:turn|switch)\s+(?:on|off)\b", text):
                return spoken(
                    "Android does not let apps toggle Wi-Fi directly. Opening the Wi-Fi settings.",
                    _action("open_settings_screen", screen="wifi"),
                )
            return spoken("Checking the connection.", _action("network_status"))
        if re.search(r"\b(?:device info|which phone|what phone|storage|how much space)\b", text):
            return spoken("Checking this device.", _action("device_info"))
        if re.search(r"\bbluetooth\b", text):
            if re.search(r"\b(?:turn|switch)\s+(?:on|off)\b", text):
                return spoken(
                    "Bluetooth toggling needs the system screen. Opening Bluetooth settings.",
                    _action("open_settings_screen", screen="bluetooth"),
                )
            return spoken("Checking Bluetooth.", _action("bluetooth_status"))
        if re.search(r"\b(?:which sim|sims?\b.*\b(?:list|show)|list sims)\b", text):
            return spoken("Let me look at your SIMs.", _action("list_sims"))

        # --- volume
        volume_match = re.search(r"(?:set\s+)?volume(?:\s+(?:to|at))?\s+(\d{1,3})\s*%?", text)
        if volume_match:
            percent = max(0, min(100, int(volume_match.group(1))))
            return spoken(f"Setting the volume to {percent} percent.",
                          _action("volume_set", stream="music", percent=percent))
        if re.search(r"\bvolume\b", text):
            return spoken("Checking the volume.", _action("volume_get", stream="music"))

        # --- brightness
        brightness_match = re.search(r"brightness(?:\s+(?:to|at))?\s+(\d{1,3})\s*%?", text)
        if brightness_match:
            percent = max(5, min(100, int(brightness_match.group(1))))
            return spoken(f"Setting brightness to {percent} percent.",
                          _action("brightness_set", percent=percent))
        if re.search(r"\bbrightness\b", text):
            return spoken("Reading the brightness.", _action("brightness_get"))

        # --- do not disturb
        if re.search(r"\b(?:do not disturb|dnd)\b", text):
            mode = None
            if re.search(r"\b(?:turn it off|turn off|disable|stop)\b", text):
                mode = "off"
            elif re.search(r"\bpriority\b", text):
                mode = "priority"
            elif re.search(r"\balarms?\b", text):
                mode = "alarms"
            elif re.search(r"\b(?:silence|silent|total)\b", text):
                mode = "silence"
            if mode:
                return spoken(f"Setting Do Not Disturb to {mode}.",
                              _action("dnd_set", mode=mode))
            return spoken("Checking Do Not Disturb.", _action("dnd_status"))

        # --- notifications
        if re.search(r"\bnotifications?\b", text):
            return spoken("Checking your notifications.", _action("notifications_summary"))

        # --- sensors
        sensor_map = {
            "light": "light", "ambient light": "light", "proximity": "proximity",
            "accelerometer": "accelerometer", "gyroscope": "gyroscope",
            "magnetometer": "magnetometer", "compass": "magnetometer",
            "pressure": "pressure", "barometer": "pressure",
            "step": "step_counter", "steps": "step_counter", "pedometer": "step_counter",
            "humidity": "humidity",
        }
        for key, sensor in sensor_map.items():
            if re.search(rf"\b{re.escape(key)}\b", text) and re.search(
                r"\b(?:read|what|how|sensor|check)\b", text
            ):
                return spoken(f"Reading the {sensor.replace('_', ' ')} sensor.",
                              _action("sensor_snapshot", sensor=sensor))

        if re.search(r"\b(?:where am i|my location|gps|location)\b", text):
            return spoken("Checking my last known location.",
                          _action("location_last_known"))

        # --- contacts
        contact_match = re.search(
            r"(?:find|look up|search)\s+(?:contact|contacts)?\s*(?:for\s+)?(.+)", original, re.I
        )
        if contact_match:
            return spoken("Searching your contacts.",
                          _action("contacts_search", query=contact_match.group(1).strip()[:60]))

        # --- calls
        call_match = re.match(r"^(?:call|dial|phone)\s+(.+)$", original, re.I)
        if call_match:
            target = call_match.group(1).strip()
            sim_match = re.search(r"\b(?:on|using|with)\s+sim\s*(?:card\s*)?(\d)\b", target)
            args: dict[str, Any] = {}
            if sim_match:
                args["sim_slot"] = max(0, int(sim_match.group(1)) - 1)
                target = re.sub(r"\b(?:on|using|with)\s+sim\s*(?:card\s*)?\d\b", "", target).strip()
            target = re.sub(r"\b(?:on|using)\s+(?:my\s+)?(?:phone|mobile)\b", "", target).strip()
            if target:
                args["contact"] = target[:80]
                return spoken(f"Calling {target}.", _action("call_contact", **args))

        # --- sms
        sms_match = re.match(
            r"^(send\s+(?:a\s+)?(?:text|sms|message)|text|message)\s+(?:to\s+)?(.+?)\s+"
            r"(?:saying|that says|with|:)\s*(.+)$",
            original,
            re.I,
        )
        if sms_match:
            wants_send = sms_match.group(1).lower().startswith("send")
            recipient = sms_match.group(2).strip()[:80]
            body = sms_match.group(3).strip()[:900]
            if wants_send:
                return spoken(
                    f"Sending a message to {recipient}. I will ask you to confirm.",
                    _action("sms_send", contact=recipient, body=body),
                )
            return spoken(
                f"Drafting a message to {recipient}. I will ask you to confirm.",
                _action("sms_compose", contact=recipient, body=body),
            )

        # --- settings screens
        if re.search(r"\bsettings\b", text) or re.search(r"\bopen\b.*\b(?:wifi|bluetooth|battery)\b", text):
            screen_map = {
                "wifi": "wifi", "wi-fi": "wifi", "bluetooth": "bluetooth",
                "battery": "battery_optimization", "notification": "notifications",
                "do not disturb": "dnd", "accessibility": "accessibility",
                "location": "location", "sound": "sound", "display": "app_info",
            }
            for key, screen in screen_map.items():
                if key in text:
                    return spoken(f"Opening the {screen.replace('_', ' ')} settings.",
                                  _action("open_settings_screen", screen=screen))
            return spoken("Opening the settings screen.",
                          _action("open_settings_screen", screen="app_info"))

        # --- navigation (optional accessibility)
        if re.match(r"^(?:go\s+)?back$", text):
            return spoken("Going back.", _action("accessibility_nav", action="back"))
        if re.match(r"^(?:go\s+)?home$", text):
            return spoken("Going home.", _action("accessibility_nav", action="home"))
        if re.search(r"\b(?:recent apps|show recents)\b", text):
            return spoken("Showing recent apps.", _action("accessibility_nav", action="recents"))

        # --- files
        file_match = re.search(
            r"(?:find|search)(?:\s+for)?\s+(?:a\s+)?(?:file|document)s?\s+"
            r"(?:called\s+|named\s+|about\s+)?(.+)",
            original,
            re.I,
        )
        if file_match:
            return spoken("Searching your documents.",
                          _action("files_search", query=file_match.group(1).strip()[:80]))

        # --- own interface
        if re.search(r"\b(?:open|show)\b.*\b(?:your|the)\b.*\b(?:interface|hologram|ui|screen)\b", text):
            return spoken("Opening my interface.", _action("open_jarvis_ui"))

        # --- app listing / launching (last, because "open ..." is generic)
        if re.search(r"\b(?:what|which|list)\b.*\bapps?\b", text):
            return spoken("Listing your applications.", _action("list_apps", limit=25))

        open_match = re.match(
            r"^(?:open|launch|start)\s+(?:up\s+)?(?:my\s+|the\s+)?(.+)$", original, re.I
        )
        if open_match:
            app_name = open_match.group(1).strip()
            if "display over" in app_name.lower() or "overlay" in app_name.lower():
                return spoken("Opening that permission screen.",
                              _action("open_display_over_other_apps"))
            return spoken(f"Opening {app_name}.", _action("launch_app", name=app_name[:80]))

        return None

    # -------------------------------------------------------------- LLM lane

    def _llm_plan(self, command: str, session: str, device_actions: set[str]) -> Plan:
        memory_block, memory_lines = self._memory_context()
        system = SYSTEM_PROMPT.format(tools=compact_prompt())
        if memory_block:
            system += "\n\nKNOWN OWNER MEMORY (use only if relevant):\n" + memory_block

        messages: list[dict[str, str]] = [{"role": "system", "content": system}]
        for turn in self.memory.recent_turns(session, limit=self.settings.memory_history_turns):
            role = "assistant" if turn["role"] == "assistant" else "user"
            messages.append({"role": role, "content": turn["content"]})
        messages.append({"role": "user", "content": command})

        try:
            raw = self.provider.complete(messages)
        except ProviderUnavailable as exc:
            return Plan(
                speech=(
                    "I can handle device commands like battery, apps, calls and volume, but I have "
                    "no AI provider configured for that question. " + str(exc)
                ),
                context=memory_lines,
                error="provider_unavailable",
            )
        except ProviderError as exc:
            self.db.audit("assistant", "provider_error", str(exc)[:200], False)
            return Plan(
                speech="My AI provider returned an error, so I could not think that through. "
                       "Device commands still work.",
                context=memory_lines,
                error="provider_error",
            )

        parsed = extract_json_object(raw)
        if parsed is None:
            return Plan(
                speech=(raw or "").strip()[:400] or "I could not form an answer.",
                context=memory_lines,
            )

        say = str(parsed.get("say") or "").strip()[:500]
        actions: list[PlannedAction] = []
        rejected: list[str] = []

        for item in parsed.get("plan") or []:
            if not isinstance(item, dict):
                continue
            tool_id = item.get("tool") or item.get("id") or item.get("action")
            raw_args = item.get("arguments", item.get("args", {}))
            clean, problems = validate_call(tool_id, raw_args)
            if problems or clean is None:
                rejected.append(str(tool_id))
                self.db.audit(
                    "assistant", "tool_rejected",
                    f"{tool_id}: {';'.join(problems)[:160]}", False,
                )
                continue
            if device_actions and tool_id not in device_actions:
                rejected.append(str(tool_id))
                self.db.audit("assistant", "tool_unavailable_on_device", str(tool_id), False)
                continue
            tool = TOOLS[tool_id]
            actions.append(
                PlannedAction(
                    id=tool_id,
                    args=clean,
                    needs_confirmation=needs_confirmation(tool_id),
                    description=tool.description,
                )
            )

        self._store_memories(parsed.get("remember"))

        if rejected and not actions:
            say = (
                (say + " " if say else "")
                + "I could not use " + ", ".join(sorted(set(rejected)))
                + " because that operation is not registered or the request was malformed."
            ).strip()

        return Plan(speech=say or "Done.", actions=actions, context=memory_lines)

    def _store_memories(self, items: Any) -> None:
        if not isinstance(items, list):
            return
        for item in items[:5]:
            if not isinstance(item, dict):
                continue
            content = str(item.get("content") or "").strip()
            if not content or find_secret(content):
                continue
            kind = str(item.get("kind") or "note").lower()
            try:
                self.memory.add(kind, content, sensitive=bool(item.get("sensitive")))
            except ValueError:
                continue

    def _llm_summary(self, command: str, results: list[dict[str, Any]]) -> str:
        system = (
            "You are JARVIS. Summarise executed device action results for the owner, spoken aloud. "
            "Report only what the results say. If ok is false, state plainly that it failed and why. "
            "Never claim a success that is not present in the results. Maximum 45 words, no markdown."
        )
        payload = json.dumps(results, default=str)[:4000]
        messages = [
            {"role": "system", "content": system},
            {"role": "user", "content": f"Command: {command}\nResults: {payload}"},
        ]
        text = self.provider.complete(messages, temperature=0.2)
        cleaned = re.sub(r"\s+", " ", (text or "").strip())
        return cleaned[:400]
