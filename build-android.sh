#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ -z "${JAVA_HOME:-}" && -d "$HOME/.local/share/comics-reader/jdk-21" ]]; then
  export JAVA_HOME="$HOME/.local/share/comics-reader/jdk-21"
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/.local/share/android-sdk" ]]; then
  export ANDROID_HOME="$HOME/.local/share/android-sdk"
fi
cd "$project_dir/android"
exec ./gradlew :app:assembleDebug --console=plain "$@"
