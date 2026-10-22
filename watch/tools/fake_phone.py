#!/usr/bin/env python3
"""Scripted stand-in for the Android companion, for exercising the watchapp in the emulator.

It speaks the AppMessage protocol from docs/PROTOCOL.md with canned data: no Hermes, no phone.

    pebble install --emulator basalt
    python3 tools/fake_phone.py basalt &      # run with pebble-tool's Python (needs libpebble2)
    python3 tools/fake_phone.py basalt headphones &   # as if headphones were connected to the phone
    pebble emu-button click select --emulator basalt
    pebble screenshot --emulator basalt

The emulator's pypkjs port is read from pebble-tool's state file (/tmp/pb-emulator.json).
"""
import json
import math
import queue
import sys
import threading
import time
import uuid

from libpebble2.communication import PebbleConnection
from libpebble2.communication.transports.websocket import WebsocketTransport
from libpebble2.services.appmessage import AppMessageService, ByteArray, CString, Uint8, Uint32

APP_UUID = uuid.UUID("094c587f-8bd7-4377-aa6b-ff55eb724b27")
KEYS = ["Cmd", "Type", "ProtoVersion", "TextBudget", "SessionId", "Title", "Flags", "Index", "Total",
        "MessageId", "Role", "Text", "State", "Detail", "Filter", "Route", "Time", "Data", "Offset", "Length"]
K = {name: 10000 + i for i, name in enumerate(KEYS)}
NAMES = {v: k for k, v in K.items()}

SESSIONS = [
    ("api_1", "Watch chat", 1 | 8),       # unread, working
    ("tg_1", "Index: groceries", 2),      # telegram
    ("cli_1", "Repo cleanup", 4),         # read-only
]
MESSAGES = [
    ("1", 0, "What's on my calendar this afternoon?"),
    ("2", 1, "You have a design review at 2pm and a dentist appointment at 4:30. "
             "Traffic to the dentist looks light, so leaving at 4:10 should be fine."),
    ("3", 0, "Remind me at 4."),
    ("4", 1, "Done. I'll remind you at 4:00pm to leave for the dentist."),
]
# Recent audio: (session id, message id, session title, preview, minutes ago, flags: 1 new, 2 telegram)
AUDIO = [
    ("tg_1", "41", "Index: groceries", "Here's the list read out: oat milk, eggs, coffee...", 3, 1 | 2),
    ("api_1", "4", "Watch chat", "Done. I'll remind you at 4:00pm to leave for the dentist.", 50, 0),
    ("cli_1", "17", "Repo cleanup", "Summary of the cleanup: 12 branches removed, 3 kept.", 60 * 26, 0),
]
# Watch speaker: a 4 second 440 Hz tone as 8 kHz signed 8-bit PCM. Reply "17" has no audio, to show the
# failure path.
TONE = bytes(int(100 * math.sin(2 * math.pi * 440 * i / 8000)) & 0xFF for i in range(8000 * 4))


def main(platform, headphones):
    state = json.load(open("/tmp/pb-emulator.json"))[platform]
    port = next(iter(state.values()))["pypkjs"]["port"]
    pebble = PebbleConnection(WebsocketTransport(f"ws://localhost:{port}/"))
    pebble.connect()
    pebble.run_async()
    svc = AppMessageService(pebble)

    def send(fields):
        out = {}
        for name, value in fields.items():
            if isinstance(value, str):
                out[K[name]] = CString(value)
            elif isinstance(value, bytes):
                out[K[name]] = ByteArray(value)
            else:
                out[K[name]] = Uint32(value) if name in ("Time", "Offset", "Length") else Uint8(value)
        svc.send_message(APP_UUID, out)
        time.sleep(0.25)

    def send_sessions(include_telegram):
        rows = [s for s in SESSIONS if include_telegram or not s[2] & 2]
        send({"Type": 3, "State": 0, "Detail": "Connected"})
        for i, (sid, title, flags) in enumerate(rows):
            send({"Type": 1, "Index": i, "Total": len(rows), "SessionId": sid, "Title": title, "Flags": flags})

    def send_messages(sid):
        for i, (mid, role, text) in enumerate(MESSAGES):
            send({"Type": 2, "SessionId": sid, "Index": i, "Total": len(MESSAGES), "MessageId": mid,
                  "Role": role, "Flags": 0, "Text": text})

    def send_audio(include_telegram):
        rows = [a for a in AUDIO if include_telegram or not a[5] & 2]
        if not rows:
            send({"Type": 8, "Index": 0, "Total": 0})
        for i, (sid, mid, title, preview, ago, flags) in enumerate(rows):
            send({"Type": 8, "Index": i, "Total": len(rows), "SessionId": sid, "MessageId": mid, "Title": title,
                  "Text": preview, "Flags": flags, "Time": int(time.time()) - ago * 60})

    # Speaker streaming runs off the receive thread, so the watch's acks keep flowing while chunks go out.
    stream = {"id": None, "chunk": 1000, "sent": 0, "allowed": 0}
    jobs = queue.Queue()

    def streamer():
        while True:
            job = jobs.get()
            if job[0] == "start":
                _, mid, chunk = job
                # The emulator's serial link garbles packets of a few KB, so stay well under the watch's limit.
                stream.update(id=mid, chunk=min(chunk, 1000), sent=0, allowed=0)
                time.sleep(1.0)  # "decoding"
                if mid == "17":
                    send({"Type": 9, "MessageId": mid, "Length": 0, "State": 0,
                          "Detail": "No Hermes audio for this reply"})
                    stream["id"] = None
                else:
                    send({"Type": 9, "MessageId": mid, "Length": len(TONE), "State": 0, "Detail": "Hermes audio"})
            elif job[0] == "pull" and job[1] == stream["id"]:
                stream["allowed"] = max(stream["allowed"], min(job[2] + job[3], len(TONE)))
                while stream["id"] == job[1] and stream["sent"] < stream["allowed"]:
                    end = min(stream["sent"] + stream["chunk"], stream["allowed"])
                    send({"Type": 10, "MessageId": job[1], "Offset": stream["sent"], "Data": TONE[stream["sent"]:end]})
                    stream["sent"] = end
            elif job[0] == "stop" and job[1] in (stream["id"], ""):
                stream["id"] = None

    threading.Thread(target=streamer, daemon=True).start()

    def on_message(tid, app, data):
        named = {NAMES.get(k, k): v for k, v in data.items()}
        print("watch ->", {k: v for k, v in named.items() if k != "Data"}, flush=True)
        cmd = named.get("Cmd")
        if cmd == 1:
            send({"Type": 11, "State": 1 if headphones else 0})
        if cmd == 1 or cmd == 2:
            send_sessions(named.get("Filter", 1))
            if cmd == 1:
                send_audio(named.get("Filter", 1))
        elif cmd == 11:
            send_audio(named.get("Filter", 1))
        elif cmd == 12:
            item = next(a for a in AUDIO if a[1] == named["MessageId"])
            send({"Type": 4, "SessionId": item[0], "MessageId": item[1], "Title": item[2],
                  "Text": item[3] + " That's everything on the list for this week.", "Flags": 2 | 8})
        elif cmd == 3:
            send_messages(named["SessionId"])
        elif cmd == 4:
            sid = named["SessionId"]
            send({"Type": 6, "SessionId": sid, "State": 2, "Detail": "Hermes is working"})
            time.sleep(1.5)
            send({"Type": 6, "SessionId": sid, "State": 3, "Detail": "Reply received"})
            send({"Type": 4, "SessionId": sid, "MessageId": "5", "Title": "Watch chat",
                  "Text": "Noted: " + named.get("Text", ""), "Flags": 0})
        elif cmd in (6, 9):
            if named.get("Route") == 1:
                send({"Type": 5, "MessageId": named["MessageId"], "State": 1, "Detail": "Playing on phone speaker"})
            elif headphones:
                send({"Type": 5, "MessageId": named["MessageId"], "State": 1, "Detail": "Playing on headphones"})
            else:
                send({"Type": 5, "MessageId": named["MessageId"], "State": 3, "Detail": "No headphones"})
        elif cmd == 13:
            jobs.put(("start", named["MessageId"], named.get("TextBudget", 1000)))
        elif cmd == 14:
            jobs.put(("pull", named["MessageId"], named.get("Offset", 0), named.get("Length", 0)))
        elif cmd == 15:
            stream["id"] = None
            jobs.put(("stop", named.get("MessageId", "")))
        elif cmd in (7, 10):
            send({"Type": 5, "MessageId": "", "State": 2 if cmd == 7 else 0, "Detail": ""})

    svc.register_handler("appmessage", on_message)
    print("fake phone ready", flush=True)
    while True:
        time.sleep(1)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "basalt", "headphones" in sys.argv[2:])
