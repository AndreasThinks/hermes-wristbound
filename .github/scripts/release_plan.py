"""Decide what a release publishes, for .github/workflows/release.yml.

Usage: python release_plan.py <tag> <prerelease true|false> <publish_store true|false> [release source]

The release source is a checkout of the release tag (default: the current directory). It is
kept apart from this script so a tag made before the script existed can still be rebuilt. Reads every part's version, checks the tag
matches the Android versionName, and decides which Pebble Store listings need this release by
asking the Store which versions it already lists, not by trusting the previous tag: a previous
release whose Store job failed or was skipped, or that was a prerelease, leaves the listing
behind. Writes key=value lines to $GITHUB_OUTPUT (or stdout) and exits 1 on a release that
must not go ahead.
"""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.request
from pathlib import Path

STORE_API = "https://appstore-api.repebble.com/api/v1/apps/id/{}"

# Pebble Store listing id per project folder (the id in the apps.repebble.com URL).
LISTINGS = {
    "watch": "22984baa50dd4b68ba9a0945",
    "pebble-plugin": "77caeb14dc3f4d3c8367ab82",
}


class PlanError(Exception):
    pass


def read_versions(root: Path) -> dict[str, str]:
    gradle = (root / "android/app/build.gradle.kts").read_text()
    android = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    voice = re.search(r"^version:\s*(\S+)", (root / "hermes-plugin/wristbound-voice/plugin.yaml").read_text(), re.M)
    if not android or not voice:
        raise PlanError("could not read the Android versionName or the voice plugin version")
    out = {"android": android.group(1), "voice": voice.group(1)}
    for key, folder in (("watch", "watch"), ("plugin", "pebble-plugin")):
        pkg = json.loads((root / folder / "package.json").read_text())
        out[key] = pkg["version"]
        out[key + "_uuid"] = pkg["pebble"]["uuid"]
    return out


def fetch_listing(listing_id: str) -> dict:
    req = urllib.request.Request(STORE_API.format(listing_id), headers={"User-Agent": "release-plan"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)["data"][0]


def needs_store(listing: dict, uuid: str, version: str) -> bool:
    """True when the listing for this app does not yet carry this version."""
    if str(listing.get("uuid", "")).lower() != uuid.lower():
        raise PlanError(f"Store listing {listing.get('id')} is app {listing.get('uuid')}, not {uuid}")
    listed = {str(c.get("version")) for c in listing.get("changelog") or []}
    latest = (listing.get("latest_release") or {}).get("version")
    if latest:
        listed.add(str(latest))
    return version not in listed


def plan(tag: str, prerelease: bool, publish_store: bool, versions: dict[str, str], fetch=fetch_listing) -> dict[str, str]:
    if tag != "v" + versions["android"]:
        raise PlanError(
            f"tag {tag} does not match the Android versionName {versions['android']}; "
            "bump android/app/build.gradle.kts or retag"
        )
    store = publish_store and not prerelease
    out = {k: versions[k] for k in ("watch", "plugin", "android", "voice")}
    out["store"] = str(store).lower()
    for key, folder in (("watch", "watch"), ("plugin", "pebble-plugin")):
        publish = False
        if store:
            try:
                listing = fetch(LISTINGS[folder])
            except PlanError:
                raise
            except Exception as e:  # fail closed: never guess whether the Store is behind
                raise PlanError(f"could not read the Store listing for {folder}: {e}") from e
            publish = needs_store(listing, versions[key + "_uuid"], versions[key])
        out[key + "_publish"] = str(publish).lower()
    return out


def main(argv: list[str]) -> int:
    tag, prerelease, publish_store = argv[1], argv[2] == "true", argv[3] == "true"
    root = Path(argv[4] if len(argv) > 4 else ".")
    try:
        result = plan(tag, prerelease, publish_store, read_versions(root))
    except PlanError as e:
        print(f"::error::{e}")
        return 1
    lines = "".join(f"{k}={v}\n" for k, v in result.items())
    print(lines, end="")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as f:
            f.write(lines)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
