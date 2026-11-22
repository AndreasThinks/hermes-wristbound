from wristbound_voice.store import ARM_TTL, CLIP_TTL, PENDING_TTL, VoiceStore, normalize


def clip(tmp_path, name="reply.mp3", data=b"ID3audio"):
    path = tmp_path / name
    path.write_bytes(data)
    return path


def test_normalize_collapses_whitespace_and_reads_text_parts():
    assert normalize("  turn   on\nthe lights ") == "turn on the lights"
    assert normalize([{"type": "text", "text": "hello"}, {"type": "image_url"}, {"type": "text", "text": "there"}]) == "hello there"
    assert normalize(None) == ""


def test_an_arm_matches_one_turn_once(home):
    store = VoiceStore(home)
    store.arm("What's the weather?", now=100)
    assert not store.take_arm("Something else", now=101)
    assert store.take_arm("  What's the   weather? ", now=102)
    assert not store.take_arm("What's the weather?", now=103)


def test_an_arm_expires(home):
    store = VoiceStore(home)
    store.arm("hello", now=100)
    assert not store.take_arm("hello", now=100 + ARM_TTL + 1)


def test_a_prefixed_prompt_still_matches(home):
    store = VoiceStore(home)
    store.arm("call mum", now=100)
    assert store.take_arm("[Andreas] call mum", now=101)


def test_arm_rejects_empty_text(home):
    store = VoiceStore(home)
    try:
        store.arm("   ")
    except ValueError:
        pass
    else:
        raise AssertionError("empty text was armed")


def test_turn_lifecycle_and_clip_lookup(home, tmp_path):
    store = VoiceStore(home)
    store.start_turn("t1", "s1", now=100)
    assert store.replies("s1", now=101) == [{
        "turn_id": "t1", "session_id": "s1", "message_id": None, "status": "pending",
        "created_at": 100, "updated_at": 100, "clips": []}]
    store.bind_message("t1", "s1", "42")
    ids = store.finish("t1", [clip(tmp_path, "a.ogg"), clip(tmp_path, "b.ogg")], now=110)
    [reply] = store.replies("s1", now=111)
    assert reply["status"] == "ready" and reply["message_id"] == "42"
    assert [c["id"] for c in reply["clips"]] == ids
    assert [c["part"] for c in reply["clips"]] == [0, 1]
    assert reply["clips"][0]["mime_type"] == "audio/ogg"
    path, mime = store.clip_file(ids[0])
    assert path.read_bytes() == b"ID3audio" and mime == "audio/ogg"


def test_failure_keeps_no_clips(home):
    store = VoiceStore(home)
    store.start_turn("t1", "s1", now=100)
    store.finish("t1", error="provider down", now=101)
    [reply] = store.replies("s1", now=102)
    assert reply["status"] == "failed" and reply["clips"] == []


def test_a_turn_that_never_finished_stops_looking_pending(home):
    store = VoiceStore(home)
    store.start_turn("t1", "s1", now=100)
    assert store.replies("s1", now=100 + PENDING_TTL + 1)[0]["status"] == "failed"


def test_clip_ids_never_reach_the_filesystem(home):
    store = VoiceStore(home)
    for bad in ("../voice.db", "voice.db", "", "a" * 24 + "/x", "ABCDEF" * 4):
        assert store.clip_file(bad) is None


def test_prune_removes_old_clips(home, tmp_path):
    store = VoiceStore(home)
    store.start_turn("t1", "s1", now=100)
    [clip_id] = store.finish("t1", [clip(tmp_path)], now=100)
    path, _ = store.clip_file(clip_id)
    store.prune(now=100 + CLIP_TTL + 1)
    assert not path.exists()
    assert store.clip_file(clip_id) is None
    assert store.replies("s1", now=100 + CLIP_TTL + 2) == []
