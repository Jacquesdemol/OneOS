#!/bin/sh
# Builds the aarch64 helpers in this directory (static, except the Bluetooth
# audio bridge) into ../assets/ and bin/.
# In Termux (without $ANDROID_NDK_HOME), automatically falls back to `zig cc`
# (musl static) or `clang -static` (ndk-multilib-native-static) for oneos_bpf_ringbuf.
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
NDK="${ANDROID_NDK_HOME:-}"
if { [ -z "$NDK" ] || [ ! -x "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]; } && [ -d "$ANDROID_HOME/ndk" ]; then
    NDK="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
fi

if [ -n "$NDK" ] && [ -x "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ]; then
    CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
    STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

    "$CC" --target=aarch64-linux-android29 -static -O2 -Wall -Wextra \
        -o "$DIR/../assets/zygote_exec" "$DIR/zygote_launcher.c"
    "$STRIP" "$DIR/../assets/zygote_exec"
    ls -la "$DIR/../assets/zygote_exec"

    "$CC" --target=aarch64-linux-android29 -static -O2 -Wall -Wextra \
        -o "$DIR/bin/socket_exec" "$DIR/socket_exec.c"
    "$STRIP" "$DIR/bin/socket_exec"
    ls -la "$DIR/bin/socket_exec"

    "$CC" --target=aarch64-linux-android29 -static -O2 -Wall -Wextra \
        -o "$DIR/../assets/oneos_essential_key" "$DIR/oneos_essential_key.c"
    "$STRIP" "$DIR/../assets/oneos_essential_key"
    ls -la "$DIR/../assets/oneos_essential_key"

    "$CC" --target=aarch64-linux-android29 -static -O2 -Wall -Wextra \
        -o "$DIR/../assets/oneos_bpf_ringbuf" "$DIR/oneos_bpf_ringbuf.c"
    "$STRIP" "$DIR/../assets/oneos_bpf_ringbuf"
    ls -la "$DIR/../assets/oneos_bpf_ringbuf"

    "$CC" --target=aarch64-linux-android29 -static -O2 -Wall -Wextra \
        -o "$DIR/../assets/oneos_storaged" "$DIR/oneos_storaged.c"
    "$STRIP" "$DIR/../assets/oneos_storaged"
    ls -la "$DIR/../assets/oneos_storaged"

    # Bluetooth audio bridge (oneos_btaudio.cpp): dynamic, it needs the phone's
    # libbinder_ndk.so; API 31 for AParcel_appendFrom.
    "${CC}++" --target=aarch64-linux-android31 -O2 -Wall -Wextra -std=c++17 -static-libstdc++ \
        -o "$DIR/../assets/oneos_btaudio" "$DIR/oneos_btaudio.cpp" -lbinder_ndk -llog -ldl
    "$STRIP" "$DIR/../assets/oneos_btaudio"
    ls -la "$DIR/../assets/oneos_btaudio"
elif command -v zig >/dev/null 2>&1; then
    echo "build.sh: NDK not found; building static helpers with zig cc (aarch64-linux-musl)"
    zig cc -target aarch64-linux-musl -static -O2 -s -Wall -Wextra \
        "$DIR/oneos_bpf_ringbuf.c" -o "$DIR/../assets/oneos_bpf_ringbuf"
    chmod 0755 "$DIR/../assets/oneos_bpf_ringbuf"
    ls -la "$DIR/../assets/oneos_bpf_ringbuf"
    zig cc -target aarch64-linux-musl -static -O2 -s -Wall -Wextra \
        "$DIR/oneos_storaged.c" -o "$DIR/../assets/oneos_storaged"
    chmod 0755 "$DIR/../assets/oneos_storaged"
    ls -la "$DIR/../assets/oneos_storaged"
elif command -v clang >/dev/null 2>&1 && { [ "$(uname -m)" = "aarch64" ] || [ "$(uname -m)" = "arm64" ]; }; then
    echo "build.sh: NDK not found; building static helpers with clang -static"
    clang -static -O2 -s -Wall -Wextra \
        "$DIR/oneos_bpf_ringbuf.c" -o "$DIR/../assets/oneos_bpf_ringbuf"
    chmod 0755 "$DIR/../assets/oneos_bpf_ringbuf"
    ls -la "$DIR/../assets/oneos_bpf_ringbuf"
    clang -static -O2 -s -Wall -Wextra \
        "$DIR/oneos_storaged.c" -o "$DIR/../assets/oneos_storaged"
    chmod 0755 "$DIR/../assets/oneos_storaged"
    ls -la "$DIR/../assets/oneos_storaged"
else
    echo "build.sh: neither ANDROID_NDK_HOME nor zig/clang found" >&2
    exit 1
fi
