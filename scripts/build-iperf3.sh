#!/bin/bash
# Cross-compile the official iperf3 release for Android and drop the binary
# into jniLibs as libiperf3.so (Android only lets apps execute files that are
# installed as native libraries).
#
# Usage: scripts/build-iperf3.sh            (run from the project root)
set -euo pipefail

VERSION=3.22
SHA256=1c0d0fb02c52626111d6e132db80edfbf27bbaff8bd9245df2a371dcb0b35a92
URL="https://github.com/esnet/iperf/releases/download/$VERSION/iperf-$VERSION.tar.gz"
API=29 # matches minSdk
ABIS=(arm64-v8a x86_64)

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK="${ANDROID_NDK:-$HOME/Library/Android/sdk/ndk/30.0.16248370}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
DL="$ROOT/third_party/iperf3-dl"
WORK="$ROOT/third_party/iperf3-build"
TARBALL="$DL/iperf-$VERSION.tar.gz"

mkdir -p "$DL"
[ -f "$TARBALL" ] || curl -fsSL "$URL" -o "$TARBALL"
echo "$SHA256  $TARBALL" | shasum -a 256 -c -

for abi in "${ABIS[@]}"; do
    case "$abi" in
        arm64-v8a) triple=aarch64-linux-android ;;
        x86_64)    triple=x86_64-linux-android ;;
    esac
    src="$WORK/$abi/iperf-$VERSION"
    /bin/rm -rf "$WORK/$abi"
    mkdir -p "$WORK/$abi"
    tar -xzf "$TARBALL" -C "$WORK/$abi"

    (
        cd "$src"
        ./configure --host="$triple" \
            --disable-shared --enable-static --without-openssl --without-sctp \
            CC="$TOOLCHAIN/$triple$API-clang" \
            AR="$TOOLCHAIN/llvm-ar" RANLIB="$TOOLCHAIN/llvm-ranlib" \
            CFLAGS="-O2 -fPIE" LDFLAGS="-pie" >/dev/null
        make -j"$(sysctl -n hw.ncpu)" >/dev/null
    )

    out="$ROOT/app/src/main/jniLibs/$abi"
    mkdir -p "$out"
    "$TOOLCHAIN/llvm-strip" -o "$out/libiperf3.so" "$src/src/iperf3"
    echo "built $out/libiperf3.so"
done
