#!/usr/bin/env bash
set -euo pipefail

SOURCE_DIR="$(cd "$(dirname "$0")" && pwd)"
OUTPUT_DIR="${1:?Use build-android.sh OUTPUT_DIRECTORY [CARGO_TARGET_DIRECTORY]}"
TARGET_DIR="${2:-$SOURCE_DIR/../../../../build/caj-rust}"
TOOL_ROOT="${COMICS_RUST_ROOT:-${HOME}/.local/share/comics-reader-native}"

if command -v cargo >/dev/null 2>&1; then
    CARGO_BIN="$(command -v cargo)"
elif [ -x "$TOOL_ROOT/cargo/bin/cargo" ]; then
    CARGO_BIN="$TOOL_ROOT/cargo/bin/cargo"
    export CARGO_HOME="${CARGO_HOME:-$TOOL_ROOT/cargo}"
    export RUSTUP_HOME="${RUSTUP_HOME:-$TOOL_ROOT/rustup}"
else
    echo "Rust 1.88 or newer is required; install rustup and the Android targets listed in README.md." >&2
    exit 1
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${HOME}/.local/share/android-sdk}}"
NDK_ROOT="${ANDROID_NDK_HOME:-$SDK_ROOT/ndk/28.2.13676358}"
NDK_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [ ! -x "$NDK_BIN/aarch64-linux-android24-clang" ]; then
    echo "Android NDK 28.2.13676358 was not found. Set ANDROID_NDK_HOME." >&2
    exit 1
fi

# Minimal development hosts may use the task-local host linker installed during setup.
if ! command -v cc >/dev/null 2>&1 && [ -x "$TOOL_ROOT/host/bin/cc" ]; then
    export CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER="$TOOL_ROOT/host/bin/cc"
fi

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$NDK_BIN/aarch64-linux-android24-clang"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$NDK_BIN/armv7a-linux-androideabi24-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$NDK_BIN/x86_64-linux-android24-clang"
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"

build_target() {
    local target="$1"
    local abi="$2"
    "$CARGO_BIN" build --locked --release --manifest-path "$SOURCE_DIR/comics-caj/Cargo.toml" --target "$target" --target-dir "$TARGET_DIR"
    mkdir -p "$OUTPUT_DIR/$abi"
    cp "$TARGET_DIR/$target/release/libcomics_caj.so" "$OUTPUT_DIR/$abi/libcomics_caj.so"
}

build_target aarch64-linux-android arm64-v8a
build_target armv7-linux-androideabi armeabi-v7a
build_target x86_64-linux-android x86_64
