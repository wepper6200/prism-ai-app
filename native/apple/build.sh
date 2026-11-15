#!/usr/bin/env bash
set -euo pipefail
sdk=${1:?iphoneos or iphonesimulator}
output=${2:?output directory}
script_dir=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$output"
output=$(cd "$output" && pwd)
python3 "$script_dir/dependencies.py" "$sdk" "$output"
cmake -S "$script_dir" -B "$output/cmake" -G Ninja \
  -DPRISM_APPLE_DEPS="$output" -DCMAKE_BUILD_TYPE=Release -DCMAKE_SYSTEM_NAME=iOS \
  -DCMAKE_OSX_SYSROOT="$(xcrun --sdk "$sdk" --show-sdk-path)" \
  -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0 \
  -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY
cmake --build "$output/cmake" --target prism_apple --parallel 3
archives=()
while IFS= read -r -d '' archive; do archives+=("$archive"); done < <(find "$output/cmake" -name '*.a' -print0)
libtool -static -o "$output/libprism_bundle.a" "${archives[@]}"
