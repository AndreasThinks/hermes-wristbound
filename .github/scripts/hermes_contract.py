"""Check that a Hermes Agent source tree still offers what Hermes Wristbound relies on.

Usage: python hermes_contract.py <path to a hermes-agent checkout>

Wristbound talks to Hermes through its dashboard (REST, native sign-in, the /api/ws JSON-RPC
gateway) and loads the wristbound-voice plugin into it. None of that is a published, versioned
API, so this reads the Hermes source and fails, naming what moved, when one of those pieces is
renamed or removed. It only reads files: nothing is installed, imported or run.
"""

from __future__ import annotations

import ast
import re
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
problems: list[str] = []
passed = 0


def sources(*dirs: str) -> str:
    text = []
    for d in dirs:
        base = root / d
        files = [base] if base.is_file() else sorted(base.rglob("*.py"))
        for f in files:
            if "/tests/" not in str(f):
                text.append(f.read_text(errors="replace"))
    return "\n".join(text)


def check(ok: bool, what: str) -> None:
    global passed
    if ok:
        passed += 1
    else:
        problems.append(what)


def function(path: str, name: str) -> ast.FunctionDef | None:
    file = root / path
    if not file.exists():
        return None
    for node in ast.walk(ast.parse(file.read_text())):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == name:
            return node
    return None


def hook_kwargs(hook: str) -> set[str]:
    """Keyword names passed wherever Hermes fires ``hook``."""
    found: set[str] = set()
    for f in (root / "agent").rglob("*.py"):
        tree = ast.parse(f.read_text(errors="replace"))
        for node in ast.walk(tree):
            if isinstance(node, ast.Call) and any(
                isinstance(a, ast.Constant) and a.value == hook for a in node.args
            ):
                found |= {k.arg for k in node.keywords if k.arg}
    return found


# -- gateway JSON-RPC (Android companion and Index plugin) -------------------------------------
gateway = sources("tui_gateway")
for method in ["session.resume", "session.create", "prompt.submit", "gateway.ping", "client.capabilities"]:
    check(f'"{method}"' in gateway, f"gateway method {method} not found in tui_gateway/")
for event in ["gateway.ready", "sessions.changed", "message.start", "message.delta", "message.complete", "tool.start"]:
    check(f'"{event}"' in gateway, f"gateway event {event} not found in tui_gateway/")

# -- dashboard routes and sign-in --------------------------------------------------------------
web = sources("hermes_cli")
for route in ["/api/status", "/api/sessions", "/api/auth/providers", "/api/auth/ws-ticket", "/api/ws",
              "/api/files/stream", "/auth/native/authorize", "/auth/native/token", "/auth/native/refresh",
              "/auth/password-login", "/api/plugins"]:
    check(route in web, f"dashboard route {route} not found in hermes_cli/")
check("X-Hermes-Session-Token" in web, "the X-Hermes-Session-Token header is no longer read")

# -- plugin hooks (wristbound-voice) -----------------------------------------------------------
pre = hook_kwargs("pre_llm_call")
check(bool(pre), "pre_llm_call is no longer fired from agent/")
for kw in ["session_id", "turn_id", "user_message", "parent_session_id"]:
    check(not pre or kw in pre, f"pre_llm_call no longer passes {kw} (passes {sorted(pre)})")
post = hook_kwargs("post_llm_call")
check(bool(post), "post_llm_call is no longer fired from agent/")
for kw in ["session_id", "turn_id", "assistant_response"]:
    check(not post or kw in post, f"post_llm_call no longer passes {kw} (passes {sorted(post)})")

# -- what the voice plugin imports from Hermes -------------------------------------------------
tts = function("tools/tts_tool.py", "text_to_speech_tool")
check(tts is not None, "tools/tts_tool.py text_to_speech_tool is gone")
if tts is not None:
    params = {a.arg for a in tts.args.args + tts.args.kwonlyargs}
    for p in ["text", "output_path"]:
        check(p in params, f"text_to_speech_tool no longer takes {p} (takes {sorted(params)})")
check(function("hermes_state.py", "_default_db_path") is not None
      or function("hermes_constants.py", "get_hermes_home") is not None,
      "neither hermes_state._default_db_path nor hermes_constants.get_hermes_home exists")
check(function("hermes_constants.py", "get_default_hermes_root") is not None
      or function("hermes_constants.py", "get_hermes_home") is not None,
      "neither get_default_hermes_root nor get_hermes_home exists in hermes_constants.py")

# -- the transcript table the voice plugin reads ------------------------------------------------
ddl = re.search(r"CREATE TABLE IF NOT EXISTS messages \((.*?)\n\);", sources("persistence", "hermes_state.py"), re.S)
check(ddl is not None, "the messages table definition was not found")
if ddl:
    for col in ["id", "session_id", "role", "content", "timestamp"]:
        check(re.search(rf"^\s*{col}\s", ddl.group(1), re.M) is not None, f"messages table has no {col} column")

rev = subprocess.run(["git", "-C", str(root), "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
print(f"Hermes {rev or '(unknown revision)'}: {passed} checks passed, {len(problems)} failed")
for p in problems:
    print(f"  - {p}")
sys.exit(1 if problems else 0)
