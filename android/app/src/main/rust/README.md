# Native CAJ reader

The Android reader calls this Rust JNI library directly. It does not execute JavaScript,
WASM, a web server or a WebView.

## Pinned source

- `vendor/caj2pdf-core-0.8.1` is the unmodified source release from
  <https://crates.io/crates/caj2pdf-core/0.8.1>, licensed under MIT (see its `LICENSE`).
- Published crate SHA-256:
  `322e176064e687ccc6156c96c1e7f9bcb208b7a0d487cf22eea2747630dd5144`.
- Original project: <https://github.com/rwv/caj2pdf-rust>.
- The JNI adapter uses `jni = 0.21.1`. Transitive versions and checksums are pinned in
  `comics-caj/Cargo.lock`. The adapter follows the application's AGPL-3.0-or-later license.

## Build

Install Rust 1.88 or newer, a Linux C linker (for example `build-essential`), Android NDK
`28.2.13676358`, and these Rust targets:

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

Gradle invokes `build-android.sh` and packages the generated libraries. The script can
also run directly:

```sh
ANDROID_NDK_HOME=/path/to/android-sdk/ndk/28.2.13676358 \
  ./build-android.sh /absolute/output/jni /absolute/output/rust-target
```

The output contains `libcomics_caj.so` for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.
The linker explicitly enables 16 KiB segment alignment.

## Storage and format limits

Only a document already imported or downloaded by the user is converted. Input uses
positioned reads; output is a private temporary PDF, deleted when its reading session
closes or conversion fails. The remote folder preview never invokes this converter.
Conversion has resource limits and a three minute deadline. Damaged page substitution
is disabled; missing pages cause an error.

The vendored engine implements the observed PDF, CAJ, KDH, HN and C8 families. HN/C8
support remains experimental in the upstream engine; unsupported profiles fail with
their original located error. C8/HN-B native text uses available Android CJK and Latin
fonts, or the selected imported Latin font. Typography can differ from CAJViewer;
missing specialized/private glyphs are reported as errors. Proprietary CNKI/SimSun
font packs are not bundled. TEB, CAA and unknown structures are refused explicitly.
