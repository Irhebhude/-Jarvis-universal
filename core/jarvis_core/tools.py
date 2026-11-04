"""The registered tool interface.

This module is the mirror of the Android action registry. A model may only ever name one of these
ids, and only with arguments declared here. There is deliberately no shell tool, no "execute
command" tool and no dynamic tool loading: the intelligence core cannot run arbitrary code by
design, not by prompt instruction.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class ArgRule:
    name: str
    type: str  # string | integer | number | boolean | enum
    required: bool = False
    enum: tuple[str, ...] = ()
    max_length: int = 0
    minimum: float | None = None
    maximum: float | None = None

    def json(self) -> dict[str, Any]:
        out: dict[str, Any] = {"name": self.name, "type": self.type, "required": self.required}
        if self.enum:
            out["enum"] = list(self.enum)
        if self.max_length:
            out["max_length"] = self.max_length
        return out


@dataclass(frozen=True)
class ToolDef:
    id: str
    description: str
    args: tuple[ArgRule, ...] = ()
    requires_confirmation: bool = False
    danger: str = "safe"

    def json(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "description": self.description,
            "args": [a.json() for a in self.args],
            "requires_confirmation": self.requires_confirmation,
            "danger": self.danger,
        }


# Actions that always need an explicit owner "yes", regardless of what the model claims.
# Kept stricter than the device layer on purpose: two independent gates.
ALWAYS_CONFIRM = {
    "call_contact",
    "sms_send",
    "sms_compose",
    "open_web_link",
    "dnd_set",
    "brightness_set",
    "accessibility_nav",
    "files_write_note",
}

SHELL_LIKE_IDS = {
    "shell", "exec", "run", "bash", "terminal", "command", "system", "sudo", "adb", "su",
}

TOOLS: dict[str, ToolDef] = {}


def _tool(tool: ToolDef) -> None:
    TOOLS[tool.id] = tool


_tool(ToolDef("battery_status", "Read the real battery level, charging state and temperature."))
_tool(ToolDef("network_status", "Report online state, transport (Wi-Fi/mobile/VPN) and metering."))
_tool(ToolDef("device_info", "Report device model, Android version, memory and free storage."))
_tool(ToolDef("jarvis_status", "Report JARVIS phase, mute state, wake-word state and core reachability."))
_tool(ToolDef("list_apps", "List launchable applications.", (
    ArgRule("query", "string", max_length=60),
    ArgRule("limit", "integer", minimum=1, maximum=60),
)))
_tool(ToolDef("launch_app", "Open an installed application by name or package id.", (
    ArgRule("name", "string", max_length=80),
    ArgRule("package", "string", max_length=120),
)))
_tool(ToolDef(
    "open_web_link", "Open an https link in the browser.",
    (ArgRule("url", "string", required=True, max_length=800),),
    requires_confirmation=True, danger="sensitive",
))
_tool(ToolDef("open_display_over_other_apps", "Open the display-over-other-apps grant screen."))
_tool(ToolDef("volume_get", "Read a stream volume.", (
    ArgRule("stream", "enum", enum=("music", "ring", "alarm", "call", "notification")),
)))
_tool(ToolDef("volume_set", "Set a stream volume percentage.", (
    ArgRule("stream", "enum", required=True, enum=("music", "ring", "alarm", "call", "notification")),
    ArgRule("percent", "integer", required=True, minimum=0, maximum=100),
), danger="sensitive"))
_tool(ToolDef("dnd_status", "Report Do Not Disturb state."))
_tool(ToolDef("dnd_set", "Change Do Not Disturb.", (
    ArgRule("mode", "enum", required=True, enum=("off", "priority", "alarms", "silence")),
), requires_confirmation=True, danger="sensitive"))
_tool(ToolDef("brightness_get", "Read screen brightness."))
_tool(ToolDef("brightness_set", "Set screen brightness percentage.", (
    ArgRule("percent", "integer", required=True, minimum=5, maximum=100),
), requires_confirmation=True, danger="sensitive"))
_tool(ToolDef("bluetooth_status", "Report Bluetooth availability and state."))
_tool(ToolDef("sensor_snapshot", "Read a hardware sensor.", (
    ArgRule(
        "sensor", "enum", required=True,
        enum=("light", "proximity", "accelerometer", "gyroscope", "magnetometer",
              "pressure", "step_counter", "humidity"),
    ),
)))
_tool(ToolDef("location_last_known", "Report the last known location fix (may be stale)."))
_tool(ToolDef("contacts_search", "Search contacts by name or number.", (
    ArgRule("query", "string", required=True, max_length=60),
    ArgRule("limit", "integer", minimum=1, maximum=20),
), danger="sensitive"))
_tool(ToolDef("list_sims", "List SIM subscriptions and slots."))
_tool(ToolDef("call_contact", "Place a call to a contact or number.", (
    ArgRule("contact", "string", max_length=80),
    ArgRule("number", "string", max_length=24),
    ArgRule("sim_slot", "integer", minimum=0, maximum=3),
), requires_confirmation=True, danger="sensitive"))
_tool(ToolDef("sms_compose", "Open the messaging app with a pre-filled SMS (nothing sent).", (
    ArgRule("contact", "string", max_length=80),
    ArgRule("number", "string", max_length=24),
    ArgRule("body", "string", max_length=900),
), requires_confirmation=True, danger="sensitive"))
_tool(ToolDef("sms_send", "Send an SMS directly.", (
    ArgRule("contact", "string", max_length=80),
    ArgRule("number", "string", max_length=24),
    ArgRule("body", "string", required=True, max_length=900),
), requires_confirmation=True, danger="destructive"))
_tool(ToolDef("notifications_summary", "Summarise allowed recent notifications.", (
    ArgRule("limit", "integer", minimum=1, maximum=20),
), danger="sensitive"))
_tool(ToolDef("notifications_set_exclusions", "Set applications JARVIS must ignore notifications from.", (
    ArgRule("packages", "string", required=True, max_length=2000),
), danger="sensitive"))
_tool(ToolDef("notifications_status", "Report notification access and buffered count."))
_tool(ToolDef("files_search", "Search indexed document files.", (
    ArgRule("query", "string", required=True, max_length=80),
    ArgRule("limit", "integer", minimum=1, maximum=25),
)))
_tool(ToolDef("files_read_text", "Read a text document by content URI.", (
    ArgRule("uri", "string", required=True, max_length=1000),
), danger="sensitive"))
_tool(ToolDef("files_write_note", "Create a text note in the granted folder (never overwrites).", (
    ArgRule("name", "string", required=True, max_length=60),
    ArgRule("body", "string", required=True, max_length=20000),
), danger="sensitive"))
_tool(ToolDef("files_storage_status", "Report available storage capabilities."))
_tool(ToolDef("open_settings_screen", "Open an Android settings screen.", (
    ArgRule(
        "screen", "enum", required=True,
        enum=("wifi", "bluetooth", "battery", "notifications", "dnd", "write_settings",
              "accessibility", "overlay", "app_info", "battery_optimization", "location", "sound"),
    ),
)))
_tool(ToolDef("accessibility_nav", "Global navigation action via accessibility.", (
    ArgRule("action", "enum", required=True,
            enum=("back", "home", "recents", "notifications", "quick_settings")),
), danger="sensitive"))
_tool(ToolDef("open_jarvis_ui", "Bring the holographic JARVIS interface to the front."))
_tool(ToolDef("set_microphone_muted", "Mute or unmute the microphone.", (
    ArgRule("muted", "boolean", required=True),
)))
_tool(ToolDef("set_wake_word_enabled", "Enable or disable the wake-word service.", (
    ArgRule("enabled", "boolean", required=True),
)))


def catalog() -> list[dict[str, Any]]:
    return [tool.json() for tool in TOOLS.values()]


def needs_confirmation(tool_id: str) -> bool:
    tool = TOOLS.get(tool_id)
    if tool is None:
        return True
    return tool.requires_confirmation or tool.id in ALWAYS_CONFIRM


def _coerce(rule: ArgRule, value: Any) -> tuple[Any, str | None]:
    if rule.type == "string":
        if not isinstance(value, str):
            return None, f"'{rule.name}' must be a string"
        if rule.max_length and len(value) > rule.max_length:
            return None, f"'{rule.name}' is too long"
        return value, None

    if rule.type == "enum":
        if not isinstance(value, str):
            return None, f"'{rule.name}' must be a string"
        match = [v for v in rule.enum if v.lower() == value.strip().lower()]
        if not match:
            return None, f"'{rule.name}' must be one of {', '.join(rule.enum)}"
        return match[0], None

    if rule.type == "integer":
        if isinstance(value, bool):
            return None, f"'{rule.name}' must be a whole number"
        try:
            number = int(str(value).strip()) if not isinstance(value, (int, float)) else int(value)
        except (TypeError, ValueError):
            return None, f"'{rule.name}' must be a whole number"
        if rule.minimum is not None and number < rule.minimum:
            return None, f"'{rule.name}' must be at least {int(rule.minimum)}"
        if rule.maximum is not None and number > rule.maximum:
            return None, f"'{rule.name}' must be at most {int(rule.maximum)}"
        return number, None

    if rule.type == "number":
        try:
            number = float(value)
        except (TypeError, ValueError):
            return None, f"'{rule.name}' must be a number"
        if rule.minimum is not None and number < rule.minimum:
            return None, f"'{rule.name}' is below the minimum"
        if rule.maximum is not None and number > rule.maximum:
            return None, f"'{rule.name}' is above the maximum"
        return number, None

    if rule.type == "boolean":
        if isinstance(value, bool):
            return value, None
        if isinstance(value, str):
            lowered = value.strip().lower()
            if lowered in ("true", "yes", "on", "1"):
                return True, None
            if lowered in ("false", "no", "off", "0"):
                return False, None
        return None, f"'{rule.name}' must be true or false"

    return None, f"unknown argument type for '{rule.name}'"


def validate_call(tool_id: Any, args: Any) -> tuple[dict[str, Any] | None, list[str]]:
    """Returns (clean_args, problems). A non-empty problem list means do not execute."""
    problems: list[str] = []
    if not isinstance(tool_id, str):
        return None, ["tool id must be a string"]
    tool_id = tool_id.strip()
    if tool_id.lower() in SHELL_LIKE_IDS:
        return None, ["shell or arbitrary command execution is not available"]

    tool = TOOLS.get(tool_id)
    if tool is None:
        return None, [f"unregistered tool '{tool_id}'"]

    if args is None:
        args = {}
    if not isinstance(args, dict):
        return None, ["arguments must be an object"]

    declared = {rule.name: rule for rule in tool.args}
    clean: dict[str, Any] = {}

    for key in args:
        if key not in declared:
            problems.append(f"unexpected argument '{key}'")

    for name, rule in declared.items():
        present = name in args and args[name] is not None
        if not present:
            if rule.required:
                problems.append(f"missing required argument '{name}'")
            continue
        value, error = _coerce(rule, args[name])
        if error:
            problems.append(error)
        else:
            clean[name] = value

    return (None if problems else clean), problems


def compact_prompt() -> str:
    lines = []
    for tool in TOOLS.values():
        arg_bits = []
        for arg in tool.args:
            bit = f"{arg.name}:{arg.type}"
            if arg.required:
                bit += "!"
            if arg.enum:
                bit += "[" + "|".join(arg.enum) + "]"
            arg_bits.append(bit)
        confirm = " (needs confirmation)" if needs_confirmation(tool.id) else ""
        lines.append(f"- {tool.id}({', '.join(arg_bits)}) {tool.description}{confirm}")
    return "\n".join(lines)


def catalog_json() -> str:
    return json.dumps(catalog(), separators=(",", ":"))
