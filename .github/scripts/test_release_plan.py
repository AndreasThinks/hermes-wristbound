import pytest

import release_plan
from release_plan import PlanError, plan

WATCH_UUID = "094c587f-8bd7-4377-aa6b-ff55eb724b27"
PLUGIN_UUID = "3a584c37-ff16-4ab8-b825-3d9c53c341ce"
VERSIONS = {
    "android": "0.6.13", "voice": "0.2.0",
    "watch": "0.6.9", "watch_uuid": WATCH_UUID,
    "plugin": "0.1.8", "plugin_uuid": PLUGIN_UUID,
}


def store(watch: list[str], plugin: list[str]):
    """A fake Store whose listings carry these versions, newest first."""
    listings = {
        "22984baa50dd4b68ba9a0945": (WATCH_UUID, watch),
        "77caeb14dc3f4d3c8367ab82": (PLUGIN_UUID, plugin),
    }
    calls = []

    def fetch(listing_id):
        calls.append(listing_id)
        uuid, versions = listings[listing_id]
        return {
            "id": listing_id, "uuid": uuid,
            "latest_release": {"version": versions[0]} if versions else None,
            "changelog": [{"version": v} for v in versions],
        }

    fetch.calls = calls
    return fetch


def test_publishes_only_what_the_store_lacks():
    out = plan("v0.6.13", False, True, VERSIONS, store(["0.6.9", "0.6.8"], ["0.1.7"]))
    assert out["watch_publish"] == "false"
    assert out["plugin_publish"] == "true"
    assert out["store"] == "true"


def test_previous_store_job_failed_or_skipped_still_publishes():
    # The previous release bumped the watch to 0.6.9 but its Store job never ran, so the
    # listing still shows 0.6.8. An unchanged version since the previous tag must not hide that.
    out = plan("v0.6.13", False, True, VERSIONS, store(["0.6.8"], ["0.1.8"]))
    assert out["watch_publish"] == "true"
    assert out["plugin_publish"] == "false"


def test_prerelease_never_reaches_the_store():
    fetch = store(["0.6.8"], ["0.1.7"])
    out = plan("v0.6.13", True, True, VERSIONS, fetch)
    assert out["store"] == "false"
    assert out["watch_publish"] == out["plugin_publish"] == "false"
    assert fetch.calls == []


def test_promoted_prerelease_publishes_on_the_full_release():
    # The prerelease skipped the Store, so the full release of the same versions publishes.
    out = plan("v0.6.13", False, True, VERSIONS, store(["0.6.8"], ["0.1.7"]))
    assert out["watch_publish"] == out["plugin_publish"] == "true"


def test_store_off_skips_without_asking_the_store():
    fetch = store(["0.6.8"], ["0.1.7"])
    out = plan("v0.6.13", False, False, VERSIONS, fetch)
    assert out["watch_publish"] == out["plugin_publish"] == "false"
    assert fetch.calls == []


def test_tag_must_match_android_version():
    with pytest.raises(PlanError, match="does not match"):
        plan("v0.6.12", False, False, VERSIONS, store([], []))


def test_unreachable_store_fails_closed():
    def down(listing_id):
        raise OSError("timed out")

    with pytest.raises(PlanError, match="could not read the Store listing"):
        plan("v0.6.13", False, True, VERSIONS, down)


def test_listing_for_another_app_fails():
    versions = dict(VERSIONS, watch_uuid="00000000-0000-0000-0000-000000000000")
    with pytest.raises(PlanError, match="not 00000000"):
        plan("v0.6.13", False, True, versions, store(["0.6.8"], ["0.1.7"]))


def old_tag_tree(root):
    """A release source laid out like v0.6.12: every part, but no release scripts."""
    (root / "android/app").mkdir(parents=True)
    (root / "android/app/build.gradle.kts").write_text('versionCode = 18\nversionName = "0.6.12"\n')
    (root / "hermes-plugin/wristbound-voice").mkdir(parents=True)
    (root / "hermes-plugin/wristbound-voice/plugin.yaml").write_text("name: wristbound-voice\nversion: 0.1.1\n")
    for folder, version, uuid in (("watch", "0.6.8", WATCH_UUID), ("pebble-plugin", "0.1.7", PLUGIN_UUID)):
        (root / folder).mkdir()
        (root / folder / "package.json").write_text(
            '{"version": "%s", "pebble": {"uuid": "%s"}}' % (version, uuid))
    return root


def test_plans_a_tag_older_than_the_planner(tmp_path, monkeypatch, capsys):
    source = old_tag_tree(tmp_path / "release")
    assert not (source / ".github").exists()
    monkeypatch.chdir(tmp_path)  # run from elsewhere, as the workflow does
    monkeypatch.delenv("GITHUB_OUTPUT", raising=False)
    assert release_plan.main(["release_plan.py", "v0.6.12", "false", "false", str(source)]) == 0
    out = capsys.readouterr().out
    assert "watch=0.6.8\n" in out and "plugin=0.1.7\n" in out and "android=0.6.12\n" in out
    assert "voice=0.1.1\n" in out and "store=false\n" in out
