# Source this file: `. scripts/env.sh`
# Points builds at the project-local toolchain created by scripts/bootstrap-toolchain.sh.
_wb_root="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
if [ -d "$_wb_root/.toolchain/jdk" ]; then export JAVA_HOME="$_wb_root/.toolchain/jdk"; fi
if [ -d "$_wb_root/.toolchain/android-sdk" ]; then export ANDROID_HOME="$_wb_root/.toolchain/android-sdk"; fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$_wb_root/.toolchain/gradle-home}"
export PATH="$JAVA_HOME/bin:$PATH"
# pebble-tool breaks if a foreign PYTHONPATH shadows its Pillow; run it via `wb_pebble`.
wb_pebble() { env -u PYTHONPATH pebble "$@"; }
unset _wb_root
