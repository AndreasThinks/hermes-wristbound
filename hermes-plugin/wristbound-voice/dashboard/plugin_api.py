"""wristbound-voice dashboard routes, mounted by Hermes at ``/api/plugins/wristbound-voice/``.

The dashboard's own auth middleware guards every ``/api/`` route, so these accept exactly the
sign-ins the Index plugin and Wristbound already use. No route takes a filesystem path.
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

from fastapi import APIRouter, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel

API_VERSION = 2


def _load_store():
    # Hermes imports this file on its own (not as part of the plugin package), so load the
    # sibling store module by path.
    name = "wristbound_voice_store"
    if name in sys.modules:
        return sys.modules[name]
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parent.parent / "store.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


_store_module = _load_store()
router = APIRouter()
_store = None


def store():
    global _store
    if _store is None:
        _store = _store_module.VoiceStore(_store_module.default_root())
    return _store


class ArmBody(BaseModel):
    text: str
    # The stored session the message goes to, when the client knows it (a resumed chat). The
    # arm then only matches a turn in that session.
    session_id: str | None = None


@router.get("/capabilities")
def capabilities():
    # 2: arms take a session_id, return an arm_id, and can be cancelled.
    return {"plugin": "wristbound-voice", "version": API_VERSION}


@router.post("/arm")
def arm(body: ArmBody):
    """Ask for a voice reply to the next turn whose prompt is exactly ``text``."""
    try:
        arm_id, expires_at = store().arm(body.text, body.session_id)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    return {"armed": True, "arm_id": arm_id, "expires_at": expires_at}


@router.delete("/arms/{arm_id}")
def cancel_arm(arm_id: int):
    """The client could not send the message it armed: forget the arm."""
    return {"cancelled": store().cancel_arm(arm_id)}


@router.get("/sessions/{session_id}/replies")
def replies(session_id: str, limit: int = 20):
    return {"session_id": session_id, "replies": store().replies(session_id, limit)}


@router.get("/clips/{clip_id}")
def clip(clip_id: str):
    found = store().clip_file(clip_id)
    if found is None:
        raise HTTPException(status_code=404, detail="No such clip")
    path, mime = found
    return FileResponse(path, media_type=mime, headers={"Cache-Control": "private, max-age=86400"})
