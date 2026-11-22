"""The two agent hooks: arm a turn before it runs, speak its reply after it is saved.

This is what ``/voice`` does on a messaging platform (``text_to_speech_tool`` on the final reply,
with the configured provider), except the audio is kept for Wristbound to fetch instead of being
sent to a chat and deleted. Hermes core is not patched: only documented plugin hooks are used.
"""

from __future__ import annotations

import json
import logging
import sqlite3
import tempfile
import threading
from contextlib import closing
from pathlib import Path
from typing import Callable, Optional

from .store import VoiceStore, default_root

logger = logging.getLogger(__name__)

Synthesize = Callable[[str, Path], list]
FindMessage = Callable[[str, str, Optional[float]], Optional[str]]


def hermes_synthesize(text: str, directory: Path) -> list:
    """Speak ``text`` with Hermes' own TTS tool and provider config. Returns the audio files."""
    from tools.tts_tool import text_to_speech_tool
    result = json.loads(text_to_speech_tool(text=text, output_path=str(directory / "reply.mp3")))
    if not result.get("success"):
        raise RuntimeError(result.get("error") or "text to speech failed")
    paths = result.get("file_paths") or [result.get("file_path")]
    return [Path(p) for p in paths if p]


def _state_db() -> Path:
    try:
        from hermes_state import _default_db_path
        return Path(_default_db_path())
    except Exception:
        from hermes_constants import get_hermes_home
        return get_hermes_home() / "state.db"


def hermes_find_message(session_id: str, reply: str, since: Optional[float] = None,
                        db: Optional[Path] = None) -> Optional[str]:
    """The id of the saved assistant row holding ``reply``, read-only; None when it is not clear.

    ``post_llm_call`` runs after the turn's transcript is saved, so the row is already there.
    Only rows written since the turn started (``since``) are considered. When none holds the reply
    verbatim (a transform hook rewrote it, say), the turn's newest assistant row is used if the
    turn wrote one; otherwise None, so a clip is never attached to an older reply."""
    db = db or _state_db()
    floor = (since - 2.0) if since is not None else None
    try:
        with closing(sqlite3.connect(f"file:{db}?mode=ro", uri=True, timeout=5)) as conn:
            if floor is None:
                rows = conn.execute(
                    "SELECT id, content FROM messages WHERE session_id = ? AND role = 'assistant' "
                    "ORDER BY id DESC LIMIT 5", (session_id,)).fetchall()
            else:
                rows = conn.execute(
                    "SELECT id, content FROM messages WHERE session_id = ? AND role = 'assistant' "
                    "AND timestamp >= ? ORDER BY id DESC LIMIT 5", (session_id, floor)).fetchall()
    except sqlite3.Error as e:
        logger.debug("wristbound-voice: could not read the session store: %s", e)
        return None
    wanted = (reply or "").strip()
    for row_id, content in rows:
        if isinstance(content, str) and content.strip() == wanted:
            return str(row_id)
    # Without a start time a mismatch could be any older reply: bind nothing rather than guess.
    return str(rows[0][0]) if rows and floor is not None else None


class VoiceHooks:
    def __init__(self, store: Optional[VoiceStore] = None, synthesize: Synthesize = hermes_synthesize,
                 find_message: FindMessage = hermes_find_message, background: bool = True):
        self._store = store
        self.synthesize = synthesize
        self.find_message = find_message
        self.background = background

    @property
    def store(self) -> VoiceStore:
        if self._store is None:
            self._store = VoiceStore(default_root())
        return self._store

    @staticmethod
    def _key(session_id, turn_id) -> Optional[str]:
        if turn_id:
            return str(turn_id)
        return f"session:{session_id}" if session_id else None

    def pre_llm_call(self, session_id=None, turn_id=None, user_message=None, **_):
        """A turn whose prompt was armed becomes pending. Returns nothing: no context is injected."""
        key = self._key(session_id, turn_id)
        if not key or not session_id:
            return None
        try:
            if self.store.take_arm(user_message, str(session_id)):
                self.store.start_turn(key, str(session_id))
                logger.info("wristbound-voice: turn %s in %s will get a voice reply", key, session_id)
        except Exception:
            logger.warning("wristbound-voice: could not check for a voice request", exc_info=True)
        return None

    def post_llm_call(self, session_id=None, turn_id=None, assistant_response=None, **_):
        """Bind the saved reply, then speak it off the turn's thread."""
        key = self._key(session_id, turn_id)
        if not key or not session_id:
            return None
        try:
            if not self.store.is_pending(key):
                return None
            text = assistant_response if isinstance(assistant_response, str) else ""
            since = self.store.started_at(key)
            self.store.bind_message(key, str(session_id), self.find_message(str(session_id), text, since))
        except Exception:
            logger.warning("wristbound-voice: could not record the reply", exc_info=True)
            return None
        if self.background:
            threading.Thread(target=self.speak, args=(key, text), name="wristbound-voice", daemon=True).start()
        else:
            self.speak(key, text)
        return None

    def speak(self, key: str, text: str) -> None:
        """Synthesize ``text`` for turn ``key``. A failure only marks the turn failed: the text reply stands."""
        if not text.strip():
            self.store.finish(key, error="empty reply")
            return
        try:
            with tempfile.TemporaryDirectory(prefix="wristbound-voice-") as tmp:
                files = self.synthesize(text, Path(tmp))
                self.store.finish(key, files)
        except Exception as e:
            logger.warning("wristbound-voice: voice reply failed for %s: %s", key, e)
            try:
                self.store.finish(key, error=str(e)[:200] or "text to speech failed")
            except Exception:
                logger.warning("wristbound-voice: could not record the failure", exc_info=True)
