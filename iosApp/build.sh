#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
cd "$root"
python3 iosApp/configure.py
swift iosApp/icon.swift "$root"
sdk="$1"
configuration="$2"
xcodebuild -project iosApp/PrismAI.xcodeproj -scheme PrismAI \
  -configuration "$configuration" -sdk "$sdk" -arch arm64 \
  -derivedDataPath "$root/iosApp/DerivedData" CODE_SIGNING_ALLOWED=NO build
if [[ "$sdk" == iphoneos ]]; then
  mkdir -p build/ios-ipa/Payload
  ditto "iosApp/DerivedData/Build/Products/$configuration-iphoneos/PrismAI.app" build/ios-ipa/Payload/PrismAI.app
  (cd build/ios-ipa && /usr/bin/zip -qr ../PrismAI-unsigned.ipa Payload)
fi
