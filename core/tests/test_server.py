from __future__ import annotations

import base64

import pytest
from fastapi.testclient import TestClient

from jarvis_core.config import Settings
from jarvis_core.server import create_app


@pytest.fixture()
def client(settings: Settings):
    app = create_app(settings)
    with TestClient(app) as test_client:
        test_client.app_state = app.state
        yield test_client


def _pair(client: TestClient) -> str:
    code, _ = client.app_state.pairing.issue_code()
    response = client.post("/pair", json={"code": code, "device_name": "test-device"})
    assert response.status_code == 200, response.text
    return response.json()["device_token"]


def test_health_needs_no_auth(client: TestClient):
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert "device_token" not in response.text


def test_protected_endpoints_require_a_token(client: TestClient):
    for path in ("/status", "/tools", "/memory/status", "/integrations/status", "/audit"):
        assert client.get(path).status_code == 401, path
    assert client.post("/assistant/command", json={"text": "battery"}).status_code == 401


def test_invalid_token_is_rejected(client: TestClient):
    response = client.get("/status", headers={"Authorization": "Bearer not-a-real-token"})
    assert response.status_code == 401


def test_browser_origin_is_refused(client: TestClient):
    token = _pair(client)
    response = client.get(
        "/status",
        headers={"Authorization": f"Bearer {token}", "Origin": "https://evil.example"},
    )
    assert response.status_code == 403
    assert response.json()["error"] == "browser_origin_refused"


def test_pairing_then_full_flow(client: TestClient):
    token = _pair(client)
    headers = {"Authorization": f"Bearer {token}"}

    status = client.get("/status", headers=headers)
    assert status.status_code == 200
    body = status.json()
    assert body["provider"] == "offline"
    assert body["configured"] is False
    assert body["memory"]["enabled"] is True

    tools = client.get("/tools", headers=headers).json()["tools"]
    ids = {t["id"] for t in tools}
    assert "battery_status" in ids
    assert "launch_app" in ids
    assert "shell" not in ids
    assert all("exec" not in t["id"] for t in tools)

    command = client.post(
        "/assistant/command",
        headers=headers,
        json={"text": "what is my battery level", "session_id": "test"},
    ).json()
    assert command["actions"][0]["id"] == "battery_status"

    plan_id = command["plan_id"]
    assert plan_id

    composed = client.post(
        "/assistant/execute",
        headers=headers,
        json={
            "plan_id": plan_id,
            "results": [
                {
                    "action_id": "battery_status",
                    "result": {"ok": True, "spoken": "Battery is at 74 percent."},
                }
            ],
        },
    ).json()
    assert composed["reply"] == "completed"
    assert "74 percent" in composed["speech"]


def test_memory_endpoints(client: TestClient):
    token = _pair(client)
    headers = {"Authorization": f"Bearer {token}"}

    added = client.post(
        "/memory/add",
        headers=headers,
        json={"kind": "preference", "content": "Prefers celsius"},
    )
    assert added.status_code == 200
    memory_id = added.json()["id"]

    listed = client.get("/memory/list", headers=headers).json()["memories"]
    assert listed[0]["content"] == "Prefers celsius"

    exported = client.get("/memory/export", headers=headers).json()
    assert exported["memories"][0]["content"] == "Prefers celsius"

    assert client.delete(f"/memory/{memory_id}", headers=headers).status_code == 200
    assert client.get("/memory/list", headers=headers).json()["memories"] == []

    cleared = client.post("/memory/clear", headers=headers).json()
    assert "memories" in cleared

    toggled = client.post("/memory/enabled", headers=headers, json={"enabled": False}).json()
    assert toggled["enabled"] is False


def test_secret_cannot_be_stored_through_the_api(client: TestClient):
    token = _pair(client)
    response = client.post(
        "/memory/add",
        headers={"Authorization": f"Bearer {token}"},
        json={"kind": "note", "content": "AIza" + "a" * 35},
    )
    assert response.status_code == 400
    assert "credential" in response.json()["detail"]


def test_transcription_reports_unavailable_without_a_model(client: TestClient):
    token = _pair(client)
    payload = {
        "format": "pcm16",
        "sample_rate": 16000,
        "audio_b64": base64.b64encode(b"\x00\x00" * 100).decode(),
    }
    response = client.post(
        "/voice/transcript",
        headers={"Authorization": f"Bearer {token}"},
        json=payload,
    )
    assert response.status_code == 501
    assert response.json()["detail"] == "local_transcription_unavailable"


def test_device_event_is_audited(client: TestClient):
    token = _pair(client)
    response = client.post(
        "/device/event",
        headers={"Authorization": f"Bearer {token}"},
        json={"type": "action_result", "payload": {"action_id": "battery_status", "ok": True}},
    )
    assert response.status_code == 200
    audit = client.get("/audit", headers={"Authorization": f"Bearer {token}"}).json()["events"]
    assert any(event["event"] == "action_result" for event in audit)


def test_integrations_status_reports_real_capabilities(client: TestClient):
    token = _pair(client)
    body = client.get(
        "/integrations/status", headers={"Authorization": f"Bearer {token}"}
    ).json()
    assert body["ai_provider"]["configured"] is False
    assert body["bridge"]["devices"] == 1
    assert "battery_status" in body["tools_registered"]


def test_pairing_rate_limit(tmp_path):
    strict = Settings(
        data_dir=tmp_path / "strict",
        pair_attempts_per_minute=2,
        rate_limit_per_minute=1000,
    )
    app = create_app(strict)
    with TestClient(app) as client:
        assert client.post("/pair", json={"code": "11111111"}).status_code == 401
        assert client.post("/pair", json={"code": "22222222"}).status_code == 401
        assert client.post("/pair", json={"code": "33333333"}).status_code == 429
