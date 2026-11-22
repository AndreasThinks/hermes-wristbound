"""Load the plugin directory (its name has a hyphen) as the package ``wristbound_voice``."""
import importlib.util
import sys
from pathlib import Path

import pytest

PLUGIN = Path(__file__).resolve().parent.parent / "wristbound-voice"


def _load_package():
    if "wristbound_voice" in sys.modules:
        return sys.modules["wristbound_voice"]
    spec = importlib.util.spec_from_file_location(
        "wristbound_voice", PLUGIN / "__init__.py", submodule_search_locations=[str(PLUGIN)])
    module = importlib.util.module_from_spec(spec)
    sys.modules["wristbound_voice"] = module
    spec.loader.exec_module(module)
    return module


_load_package()


@pytest.fixture
def home(tmp_path, monkeypatch):
    root = tmp_path / "voice"
    monkeypatch.setenv("WRISTBOUND_VOICE_HOME", str(root))
    return root
