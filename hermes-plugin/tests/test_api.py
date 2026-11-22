import importlib.util
from pathlib import Path

import pytest

fastapi = pytest.importorskip("fastapi")
from fastapi import FastAPI
from fastapi.testclient import TestClient

API = Path(__file__).resolve().parent.parent / "wristbound-voice" / "dashboard" / "plugin_api.py"


@pytest.fixture
def client(home):
    spec = importlib.util.spec_from_file_location("wristbound_voice_api_test", API)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module._store = None
    app = FastAPI()
    app.include_router(module.router, prefix="/api/plugins/wristbound-voice")
    return TestClient(app), module


def test_capabilities(client):
    c, _ = client
    assert c.get("/api/plugins/wristbound-voice/capabilities").json() == {"plugin": "wristbound-voice", "version": 2}


def test_arm_then_replies_then_clip(client, tmp_path):
    c, module = client
    r = c.post("/api/plugins/wristbound-voice/arm", json={"text": "hello"})
    assert r.status_code == 200 and r.json()["armed"] is True
    store = module.store()
    assert store.take_arm("hello")
    store.start_turn("t1", "s1")
    audio = tmp_path / "x.ogg"
    audio.write_bytes(b"OggS....")
    [clip_id] = store.finish("t1", [audio])
    body = c.get("/api/plugins/wristbound-voice/sessions/s1/replies").json()
    assert body["replies"][0]["clips"][0]["id"] == clip_id
    got = c.get(f"/api/plugins/wristbound-voice/clips/{clip_id}")
    assert got.status_code == 200 and got.content == b"OggS...." and got.headers["content-type"] == "audio/ogg"


def test_bad_requests(client):
    c, _ = client
    assert c.post("/api/plugins/wristbound-voice/arm", json={"text": "  "}).status_code == 400
    assert c.get("/api/plugins/wristbound-voice/clips/..%2Fvoice.db").status_code == 404
    assert c.get("/api/plugins/wristbound-voice/clips/" + "0" * 24).status_code == 404
    assert c.get("/api/plugins/wristbound-voice/sessions/none/replies").json()["replies"] == []


def test_an_arm_for_a_chat_can_be_cancelled(client):
    c, module = client
    r = c.post("/api/plugins/wristbound-voice/arm", json={"text": "lights off", "session_id": "s1"}).json()
    assert isinstance(r["arm_id"], int)
    assert c.delete(f"/api/plugins/wristbound-voice/arms/{r['arm_id']}").json() == {"cancelled": True}
    assert not module.store().take_arm("lights off", session_id="s1")
    assert c.get("/api/plugins/wristbound-voice/capabilities").json()["version"] == 2

