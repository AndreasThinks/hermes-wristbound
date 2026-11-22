"""Storage for wristbound-voice: armed captures, spoken turns and their clips.

Plain stdlib (sqlite3 + files) so it is shared by the agent hooks (in whichever process runs the
turn) and the dashboard routes (in the web server process). Nothing here imports Hermes.

Lifecycle of one spoken reply:

1. ``arm(text, session_id)``: a client (the Index plugin) says the next turn whose prompt is
   ``text`` should be answered with voice; with ``session_id``, only a turn in that session.
   Arms expire after ``ARM_TTL``, are used at most once, and are cancelled when the send fails.
2. ``start_turn``: the ``pre_llm_call`` hook found a matching arm. The turn is ``pending`` before
   any reply exists, so clients know audio is coming.
3. ``bind_message``: ``post_llm_call`` attached the saved assistant row's id.
4. ``finish``: speech is ``ready`` (with clips) or ``failed``.
"""

from __future__ import annotations

import os
import re
import secrets
import shutil
import sqlite3
import time
import unicodedata
from contextlib import closing
from pathlib import Path
from typing import Iterable, Optional

ARM_TTL = 10 * 60
# A turn that never finished (interrupted, crashed process) stops looking pending after this.
PENDING_TTL = 10 * 60
CLIP_TTL = 7 * 24 * 3600
MAX_TEXT = 8000

PENDING, READY, FAILED = "pending", "ready", "failed"

_SCHEMA = """
CREATE TABLE IF NOT EXISTS arms (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    text TEXT NOT NULL,
    created_at REAL NOT NULL,
    used_at REAL,
    session_id TEXT
);
CREATE TABLE IF NOT EXISTS turns (
    turn_key TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    message_id TEXT,
    status TEXT NOT NULL,
    error TEXT,
    created_at REAL NOT NULL,
    updated_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS turns_session ON turns(session_id, created_at);
CREATE TABLE IF NOT EXISTS clips (
    id TEXT PRIMARY KEY,
    turn_key TEXT NOT NULL,
    part INTEGER NOT NULL,
    file TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    created_at REAL NOT NULL
);
"""

_MIME = {".ogg": "audio/ogg", ".opus": "audio/ogg", ".oga": "audio/ogg", ".mp3": "audio/mpeg",
         ".wav": "audio/wav", ".m4a": "audio/mp4", ".aac": "audio/mp4", ".flac": "audio/flac"}
_CLIP_ID = re.compile(r"^[0-9a-f]{24}$")
# What Hermes may put in front of the user's words on the way to the model: a bracketed tag
# ("[Andreas]", "<telegram>") or a short "name:" label. Plain words in front never count, so
# arming "yes" does not speak the reply to "please answer yes".
_PREFIX_TAG = re.compile(r"^(\[[^\]]{1,80}\]|<[^>]{1,80}>|\([^)]{1,80}\)|[^\s:]{1,40}:)$")


def normalize(text: object) -> str:
    """Prompt text as compared for matching: NFC, whitespace collapsed, no case folding."""
    if isinstance(text, list):  # multimodal content: keep only the text parts
        text = " ".join(p.get("text", "") for p in text if isinstance(p, dict) and p.get("type") == "text")
    if not isinstance(text, str):
        return ""
    return " ".join(unicodedata.normalize("NFC", text).split())


class VoiceStore:
    def __init__(self, root: Path):
        self.root = Path(root)
        self.clips_dir = self.root / "clips"
        self.db_path = self.root / "voice.db"

    # -- plumbing ---------------------------------------------------------------------------------

    def _connect(self) -> sqlite3.Connection:
        self.clips_dir.mkdir(parents=True, exist_ok=True)
        conn = sqlite3.connect(self.db_path, timeout=10, isolation_level=None)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.executescript(_SCHEMA)
        # Stores made by 0.1.0 have no arms.session_id column.
        if "session_id" not in {r["name"] for r in conn.execute("PRAGMA table_info(arms)")}:
            conn.execute("ALTER TABLE arms ADD COLUMN session_id TEXT")
        return conn

    # -- arms -------------------------------------------------------------------------------------

    def arm(self, text: str, session_id: Optional[str] = None, now: Optional[float] = None) -> tuple[int, float]:
        """Arm the next turn whose prompt is ``text`` (in ``session_id``, when given).

        Returns the arm's id, for :meth:`cancel_arm`, and when it expires."""
        now = time.time() if now is None else now
        norm = normalize(text)
        if not norm:
            raise ValueError("text is required")
        if len(norm) > MAX_TEXT:
            raise ValueError("text is too long")
        with closing(self._connect()) as conn:
            conn.execute("DELETE FROM arms WHERE created_at < ?", (now - ARM_TTL,))
            cur = conn.execute("INSERT INTO arms(text, created_at, session_id) VALUES (?, ?, ?)",
                               (norm, now, session_id or None))
        return cur.lastrowid, now + ARM_TTL

    def cancel_arm(self, arm_id: int) -> bool:
        """Drop an arm that was never used: the client could not send its message."""
        with closing(self._connect()) as conn:
            cur = conn.execute("DELETE FROM arms WHERE id = ? AND used_at IS NULL", (int(arm_id),))
        return cur.rowcount > 0

    @staticmethod
    def _matches(armed: str, prompt: str) -> bool:
        """The prompt is the armed text, or the armed text behind a tag Hermes added."""
        if prompt == armed:
            return True
        if not prompt.endswith(" " + armed):
            return False
        return bool(_PREFIX_TAG.match(prompt[: -len(armed) - 1].strip()))

    def take_arm(self, prompt: object, session_id: Optional[str] = None, now: Optional[float] = None) -> bool:
        """Use the oldest live arm matching ``prompt`` in ``session_id``. True when this turn should be spoken.

        An arm made for a session only matches a turn in it; one made for a new session (no id
        known yet) matches any session. A session's own arms are tried first."""
        now = time.time() if now is None else now
        norm = normalize(prompt)
        if not norm:
            return False
        with closing(self._connect()) as conn:
            conn.execute("BEGIN IMMEDIATE")
            try:
                rows = conn.execute(
                    "SELECT id, text, session_id FROM arms WHERE used_at IS NULL AND created_at >= ? "
                    "AND (session_id IS NULL OR session_id = ?) ORDER BY session_id IS NULL, id",
                    (now - ARM_TTL, str(session_id or ""))).fetchall()
                match = next((r for r in rows if self._matches(r["text"], norm)), None)
                if match is not None:
                    conn.execute("UPDATE arms SET used_at = ? WHERE id = ?", (now, match["id"]))
                conn.execute("COMMIT")
            except BaseException:
                conn.execute("ROLLBACK")
                raise
        return match is not None

    # -- turns ------------------------------------------------------------------------------------

    def start_turn(self, turn_key: str, session_id: str, now: Optional[float] = None) -> None:
        now = time.time() if now is None else now
        with closing(self._connect()) as conn:
            conn.execute(
                "INSERT OR REPLACE INTO turns(turn_key, session_id, status, created_at, updated_at) "
                "VALUES (?, ?, ?, ?, ?)", (turn_key, session_id, PENDING, now, now))
        self.prune(now)

    def started_at(self, turn_key: str) -> Optional[float]:
        """When the turn was marked pending, or None for an unknown turn."""
        with closing(self._connect()) as conn:
            row = conn.execute("SELECT created_at FROM turns WHERE turn_key = ?", (turn_key,)).fetchone()
        return None if row is None else row["created_at"]

    def is_pending(self, turn_key: str) -> bool:
        with closing(self._connect()) as conn:
            row = conn.execute("SELECT status FROM turns WHERE turn_key = ?", (turn_key,)).fetchone()
        return row is not None and row["status"] == PENDING

    def bind_message(self, turn_key: str, session_id: str, message_id: Optional[str]) -> None:
        with closing(self._connect()) as conn:
            conn.execute("UPDATE turns SET message_id = ?, session_id = ?, updated_at = ? WHERE turn_key = ?",
                         (message_id, session_id, time.time(), turn_key))

    def finish(self, turn_key: str, files: Iterable[Path] = (), error: Optional[str] = None,
               now: Optional[float] = None) -> list[str]:
        """Move synthesized ``files`` into the store and mark the turn ready (or failed)."""
        now = time.time() if now is None else now
        ids: list[str] = []
        with closing(self._connect()) as conn:
            for part, src in enumerate(files):
                src = Path(src)
                ext = src.suffix.lower() if src.suffix.lower() in _MIME else ".mp3"
                clip_id = secrets.token_hex(12)
                target = self.clips_dir / f"{clip_id}{ext}"
                if src.resolve().parent != self.clips_dir.resolve():
                    shutil.copyfile(src, target)
                else:
                    os.replace(src, target)
                conn.execute("INSERT INTO clips(id, turn_key, part, file, mime_type, created_at) VALUES (?,?,?,?,?,?)",
                             (clip_id, turn_key, part, target.name, _MIME.get(ext, "audio/mpeg"), now))
                ids.append(clip_id)
            status = READY if ids and not error else FAILED
            conn.execute("UPDATE turns SET status = ?, error = ?, updated_at = ? WHERE turn_key = ?",
                         (status, None if status == READY else (error or "no audio"), now, turn_key))
        return ids

    # -- reads (dashboard routes) -------------------------------------------------------------------

    def replies(self, session_id: str, limit: int = 20, now: Optional[float] = None) -> list[dict]:
        """The latest spoken turns of a session, newest first."""
        now = time.time() if now is None else now
        with closing(self._connect()) as conn:
            turns = conn.execute(
                "SELECT * FROM turns WHERE session_id = ? ORDER BY created_at DESC LIMIT ?",
                (session_id, max(1, min(limit, 100)))).fetchall()
            out = []
            for t in turns:
                status = t["status"]
                if status == PENDING and t["created_at"] < now - PENDING_TTL:
                    status = FAILED  # never finished: stop clients waiting for it
                clips = conn.execute("SELECT id, part, mime_type, created_at FROM clips WHERE turn_key = ? ORDER BY part",
                                     (t["turn_key"],)).fetchall() if status == READY else []
                out.append({
                    "turn_id": t["turn_key"], "session_id": t["session_id"], "message_id": t["message_id"],
                    "status": status, "created_at": t["created_at"], "updated_at": t["updated_at"],
                    "clips": [{"id": c["id"], "part": c["part"], "mime_type": c["mime_type"],
                               "created_at": c["created_at"], "expires_at": c["created_at"] + CLIP_TTL} for c in clips],
                })
        return out

    def clip_file(self, clip_id: str) -> Optional[tuple[Path, str]]:
        """The file and mime type for ``clip_id``; None for an unknown, malformed or missing clip."""
        if not _CLIP_ID.match(clip_id or ""):
            return None
        with closing(self._connect()) as conn:
            row = conn.execute("SELECT file, mime_type FROM clips WHERE id = ?", (clip_id,)).fetchone()
        if row is None:
            return None
        path = self.clips_dir / row["file"]
        if path.is_symlink() or not path.is_file() or path.resolve().parent != self.clips_dir.resolve():
            return None
        return path, row["mime_type"]

    # -- housekeeping -----------------------------------------------------------------------------

    def prune(self, now: Optional[float] = None) -> None:
        """Drop clips and turns older than ``CLIP_TTL``, and spent arms."""
        now = time.time() if now is None else now
        cutoff = now - CLIP_TTL
        with closing(self._connect()) as conn:
            for row in conn.execute("SELECT file FROM clips WHERE created_at < ?", (cutoff,)).fetchall():
                try:
                    (self.clips_dir / row["file"]).unlink()
                except FileNotFoundError:
                    pass
            conn.execute("DELETE FROM clips WHERE created_at < ?", (cutoff,))
            conn.execute("DELETE FROM turns WHERE updated_at < ?", (cutoff,))
            conn.execute("DELETE FROM arms WHERE created_at < ?", (now - ARM_TTL,))


def default_root() -> Path:
    """``<hermes root>/wristbound-voice``, shared by every profile and process on this machine."""
    override = os.environ.get("WRISTBOUND_VOICE_HOME")
    if override:
        return Path(override)
    try:
        from hermes_constants import get_default_hermes_root
        return get_default_hermes_root() / "wristbound-voice"
    except Exception:
        from hermes_constants import get_hermes_home
        return get_hermes_home() / "wristbound-voice"
