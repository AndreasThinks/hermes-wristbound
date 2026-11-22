"""wristbound-voice: per-capture voice replies for Hermes Wristbound and the Index plugin.

A client arms one prompt (``POST /api/plugins/wristbound-voice/arm``), sends it with the normal
``prompt.submit``, and gets the reply's audio from ``/api/plugins/wristbound-voice/``. See README.md.
"""

from __future__ import annotations

from .hooks import VoiceHooks

_hooks = VoiceHooks()


def register(ctx):
    ctx.register_hook("pre_llm_call", _hooks.pre_llm_call)
    ctx.register_hook("post_llm_call", _hooks.post_llm_call)
