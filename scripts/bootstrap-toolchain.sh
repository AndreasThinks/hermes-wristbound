#!/usr/bin/env bash
# Downloads a project-local JDK, Android SDK packages and Gradle into .toolchain/ (gitignored).
# No root privileges and no system changes.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
tc="$root/.toolchain"; mkdir -p "$tc/dl"
cd "$tc"
if [ ! -x jdk/bin/javac ]; then
  curl -fsSL -o dl/jdk21.tar.gz "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar xzf dl/jdk21.tar.gz && mv jdk-21* jdk
fi
if [ ! -x android-sdk/cmdline-tools/latest/bin/sdkmanager ]; then
  curl -fsSL -o dl/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
  mkdir -p android-sdk/cmdline-tools && (cd android-sdk/cmdline-tools && unzip -q ../../dl/cmdtools.zip && mv cmdline-tools latest)
fi
export JAVA_HOME="$tc/jdk"
sdkm() { env -u PYTHONPATH android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root="$tc/android-sdk" "$@"; }
yes | sdkm --licenses >/dev/null 2>&1 || true
sdkm "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" >/dev/null
echo "Toolchain ready in $tc"
