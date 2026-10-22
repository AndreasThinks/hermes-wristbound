#!/usr/bin/env python3
"""Scripted stand-in for the Android companion, for exercising the watchapp in the emulator.

It speaks the AppMessage protocol from docs/PROTOCOL.md with canned data: no Hermes, no phone.

    pebble install --emulator basalt
    python3 tools/fake_phone.py basalt &      # run with pebble-tool's Python (needs libpebble2)
    pebble emu-button click select --emulator basalt
    pebble screenshot --emulator basalt

The emulator's pypkjs port is read from pebble-tool's state file (/tmp/pb-emulator.json).
"""
import json
import sys
import time
import uuid

from libpebble2.communication import PebbleConnection
from libpebble2.communication.transports.websocket import WebsocketTransport
from libpebble2.services.appmessage import AppMessageService, CString, Uint8

APP_UUID = uuid.UUID("094c587f-8bd7-4377-aa6b-ff55eb724b27")
KEYS = ["Cmd", "Type", "ProtoVersion", "TextBudget", "SessionId", "Title", "Flags", "Index", "Total",
        "MessageId", "Role", "Text", "State", "Detail", "Filter", "Route"]
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


def main(platform):
    state = json.load(open("/tmp/pb-emulator.json"))[platform]
    port = next(iter(state.values()))["pypkjs"]["port"]
    pebble = PebbleConnection(WebsocketTransport(f"ws://localhost:{port}/"))
    pebble.connect()
    pebble.run_async()
    svc = AppMessageService(pebble)

    def send(fields):
        out = {}
        for name, value in fields.items():
            out[K[name]] = CString(value) if isinstance(value, str) else Uint8(value)
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

    def on_message(tid, app, data):
        named = {NAMES.get(k, k): v for k, v in data.items()}
        print("watch ->", named, flush=True)
        cmd = named.get("Cmd")
        if cmd == 1 or cmd == 2:
            send_sessions(named.get("Filter", 1))
        elif cmd == 3:
            send_messages(named["SessionId"])
        elif cmd == 4:
            sid = named["SessionId"]
            send({"Type": 6, "SessionId": sid, "State": 2, "Detail": "Hermes is working"})
            time.sleep(1.5)
            send({"Type": 6, "SessionId": sid, "State": 3, "Detail": "Reply received"})
            send({"Type": 4, "SessionId": sid, "MessageId": "5", "Title": "Watch chat",
                  "Text": "Noted: " + named.get("Text", ""), "Flags": 0})
        elif cmd == 6:
            if named.get("Route") == 1:
                send({"Type": 5, "MessageId": named["MessageId"], "State": 1, "Detail": "Playing on speaker"})
            else:
                send({"Type": 5, "MessageId": named["MessageId"], "State": 3, "Detail": "No headphones"})
        elif cmd in (7, 10):
            send({"Type": 5, "MessageId": "", "State": 2 if cmd == 7 else 0, "Detail": ""})

    svc.register_handler("appmessage", on_message)
    print("fake phone ready", flush=True)
    while True:
        time.sleep(1)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "basalt")
