import sqlite3
from pathlib import Path

from wristbound_voice.hooks import VoiceHooks, hermes_find_message
from wristbound_voice.store import VoiceStore


def fake_tts(calls):
    def synthesize(text, directory):
        calls.append(text)
        out = Path(directory) / "reply.mp3"
        out.write_bytes(b"ID3" + text.encode())
        return [out]
    return synthesize


def hooks(home, calls, find=lambda s, r, since=None: "7"):
    return VoiceHooks(VoiceStore(home), synthesize=fake_tts(calls), find_message=find, background=False)


def test_an_armed_capture_gets_a_voice_reply(home):
    calls = []
    h = hooks(home, calls)
    h.store.arm("turn on the porch light")
    assert h.pre_llm_call(session_id="s1", turn_id="t1", user_message="turn on the porch light") is None
    assert h.store.replies("s1")[0]["status"] == "pending"
    h.post_llm_call(session_id="s1", turn_id="t1", assistant_response="Done, the porch light is on.")
    assert calls == ["Done, the porch light is on."]
    [reply] = h.store.replies("s1")
    assert reply["status"] == "ready" and reply["message_id"] == "7" and len(reply["clips"]) == 1


def test_unarmed_turns_are_never_spoken(home):
    calls = []
    h = hooks(home, calls)
    h.store.arm("first capture")
    h.pre_llm_call(session_id="s1", turn_id="t1", user_message="typed on the desktop")
    h.post_llm_call(session_id="s1", turn_id="t1", assistant_response="answer")
    assert calls == [] and h.store.replies("s1") == []


def test_only_the_capture_just_dictated_is_spoken(home):
    calls = []
    h = hooks(home, calls)
    h.store.arm("what time is it")
    h.pre_llm_call(session_id="s1", turn_id="t1", user_message="what time is it")
    h.post_llm_call(session_id="s1", turn_id="t1", assistant_response="Five o'clock.")
    # The next message in the same chat, typed elsewhere, stays text only.
    h.pre_llm_call(session_id="s1", turn_id="t2", user_message="thanks")
    h.post_llm_call(session_id="s1", turn_id="t2", assistant_response="You're welcome.")
    assert calls == ["Five o'clock."]
    assert [r["turn_id"] for r in h.store.replies("s1")] == ["t1"]


def test_tts_failure_marks_the_turn_failed_without_raising(home):
    def broken(text, directory):
        raise RuntimeError("no TTS provider configured")
    h = VoiceHooks(VoiceStore(home), synthesize=broken, find_message=lambda s, r, since=None: "7", background=False)
    h.store.arm("hello")
    h.pre_llm_call(session_id="s1", turn_id="t1", user_message="hello")
    h.post_llm_call(session_id="s1", turn_id="t1", assistant_response="Hi!")
    [reply] = h.store.replies("s1")
    assert reply["status"] == "failed" and reply["clips"] == []


def test_hooks_never_raise_into_the_turn(home):
    class Broken(VoiceStore):
        def take_arm(self, *a, **k):
            raise OSError("disk full")
        def is_pending(self, *a, **k):
            raise OSError("disk full")
    h = VoiceHooks(Broken(home), synthesize=fake_tts([]), background=False)
    assert h.pre_llm_call(session_id="s1", turn_id="t1", user_message="x") is None
    assert h.post_llm_call(session_id="s1", turn_id="t1", assistant_response="y") is None


def test_falls_back_to_the_session_when_there_is_no_turn_id(home):
    calls = []
    h = hooks(home, calls)
    h.store.arm("hello")
    h.pre_llm_call(session_id="s1", user_message="hello")
    h.post_llm_call(session_id="s1", assistant_response="Hi!")
    assert calls == ["Hi!"]


def test_background_speech_runs_off_the_turn_thread(home):
    import threading
    done = threading.Event()
    seen = []
    def synth(text, directory):
        seen.append(threading.current_thread().name)
        done.set()
        out = Path(directory) / "r.mp3"
        out.write_bytes(b"x")
        return [out]
    h = VoiceHooks(VoiceStore(home), synthesize=synth, find_message=lambda s, r, since=None: "1", background=True)
    h.store.arm("hello")
    h.pre_llm_call(session_id="s1", turn_id="t1", user_message="hello")
    h.post_llm_call(session_id="s1", turn_id="t1", assistant_response="Hi!")
    assert done.wait(5) and seen == ["wristbound-voice"]


def _db(tmp_path):
    db = tmp_path / "state.db"
    with sqlite3.connect(db) as conn:
        conn.execute("CREATE TABLE messages (id INTEGER PRIMARY KEY, session_id TEXT, role TEXT, content TEXT, timestamp REAL)")
        conn.executemany("INSERT INTO messages(id, session_id, role, content, timestamp) VALUES (?,?,?,?,?)", [
            (1, "s1", "user", "hi", 100), (2, "s1", "assistant", "Hello!", 101), (3, "s1", "user", "again", 200),
            (4, "s1", "assistant", "Hello again!", 201), (5, "s2", "assistant", "other chat", 202)])
    return db


def test_find_message_reads_the_saved_assistant_row(tmp_path):
    db = _db(tmp_path)
    assert hermes_find_message("s1", "Hello!", db=db) == "2"
    assert hermes_find_message("s1", "Hello again!\n", since=200, db=db) == "4"
    assert hermes_find_message("s3", "x", db=db) is None
    assert hermes_find_message("s1", "x", db=tmp_path / "missing.db") is None


def test_find_message_never_binds_a_clip_to_an_older_reply(tmp_path):
    db = _db(tmp_path)
    # The turn's own row, rewritten by a transform hook: still this turn's newest row.
    assert hermes_find_message("s1", "not saved verbatim", since=200, db=db) == "4"
    # The turn wrote no row yet: nothing, rather than the previous reply.
    assert hermes_find_message("s1", "not saved verbatim", since=300, db=db) is None
    # No start time to go by: a mismatch binds nothing.
    assert hermes_find_message("s1", "not saved verbatim", db=db) is None
    # An older reply with the same words is not this turn's.
    assert hermes_find_message("s1", "Hello!", since=200, db=db) == "4"


def test_post_llm_call_passes_the_turn_start(home):
    seen = []
    h = hooks(home, [], find=lambda s, r, since=None: seen.append(since) or "9")
    h.store.arm("hello")
    h.pre_llm_call(session_id="s1", turn_id="t1", user_message="hello")
    h.post_llm_call(session_id="s1", turn_id="t1", assistant_response="Hi!")
    assert seen and seen[0] is not None


def test_an_arm_for_one_chat_does_not_speak_another(home):
    calls = []
    h = hooks(home, calls)
    h.store.arm("status please", session_id="s-index")
    h.pre_llm_call(session_id="s-telegram", turn_id="t1", user_message="status please")
    h.post_llm_call(session_id="s-telegram", turn_id="t1", assistant_response="All quiet.")
    h.pre_llm_call(session_id="s-index", turn_id="t2", user_message="status please")
    h.post_llm_call(session_id="s-index", turn_id="t2", assistant_response="All quiet here too.")
    assert calls == ["All quiet here too."]
