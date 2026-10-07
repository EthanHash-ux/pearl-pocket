#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
sdk_dir="${ANDROID_SDK_ROOT:-/opt/pearl-android-sdk}"
go_binary="${PEARL_GO:-/opt/pearl-build-tools/go/bin/go}"
if [[ ! -x "$go_binary" ]]; then go_binary="$(command -v go)"; fi
ndk_tools="$sdk_dir/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin"
expected_commit=2f8b770cac8f8b74be05c1fc51c2baeb76ce0701
test "$(git -C "$project_dir/../pearl-upstream" rev-parse HEAD)" = "$expected_commit" || { echo 'Pearl upstream commit mismatch' >&2; exit 1; }
git -C "$project_dir/../pearl-upstream" diff --quiet HEAD -- go.mod go.sum node/btcec node/btcutil node/chaincfg node/txscript node/wire xmss || { echo 'Signing source has unreviewed changes' >&2; exit 1; }
cd "$project_dir/core"
for target in arm64-v8a x86_64; do
  if [[ "$target" == arm64-v8a ]]; then arch=arm64; compiler=aarch64-linux-android26-clang; else arch=amd64; compiler=x86_64-linux-android26-clang; fi
  mkdir -p "$project_dir/app/src/main/jniLibs/$target"
  GOOS=android GOARCH="$arch" CGO_ENABLED=1 CC="$ndk_tools/$compiler" "$go_binary" build -buildvcs=false -trimpath -buildmode=c-shared -ldflags='-s -w -extldflags=-Wl,-z,max-page-size=16384' -o "$project_dir/app/src/main/jniLibs/$target/libpearlcore.so" ./cmd/shared
  rm -f "$project_dir/app/src/main/jniLibs/$target/libpearlcore.h"
done
