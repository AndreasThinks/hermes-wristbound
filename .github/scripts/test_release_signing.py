"""Release signing must not depend on Gradle's platform-specific debug-key lookup."""
from pathlib import Path

WORKFLOW = Path(__file__).parents[1] / "workflows" / "release.yml"


def test_release_passes_the_restored_keystore_explicitly_to_gradle():
    source = WORKFLOW.read_text()
    assert '"$RUNNER_TEMP/wristbound-signing/debug.keystore"' in source
    assert '-Pandroid.injected.signing.store.file="$RUNNER_TEMP/wristbound-signing/debug.keystore"' in source
    assert '-Pandroid.injected.signing.store.password=android' in source
    assert '-Pandroid.injected.signing.key.alias=androiddebugkey' in source
    assert '-Pandroid.injected.signing.key.password=android' in source


def test_signing_regression_runs_in_both_ci_and_release():
    for name in ("build.yml", "release.yml"):
        assert 'test_release_signing.py' in (WORKFLOW.parent / name).read_text()
