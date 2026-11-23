#!/usr/bin/env bash
# Builds an unsigned Release archive and packages build/Beaver.ipa for sideloading.
set -euo pipefail
cd "$(dirname "$0")/.."

dev_dir="$(xcode-select -p)"
case "$dev_dir" in
  *.app/Contents/Developer*) ;;
  *) echo "xcode-select points to '$dev_dir', not an Xcode.app. Run: sudo xcode-select -s /Applications/Xcode.app" >&2; exit 1 ;;
esac
command -v xcodegen >/dev/null || { echo "xcodegen not found (brew install xcodegen)" >&2; exit 1; }

xcodegen generate

rm -rf build/Beaver.xcarchive build/Payload build/Beaver.ipa
mkdir -p build

xcodebuild -project Beaver.xcodeproj -scheme Beaver -configuration Release -sdk iphoneos \
  -archivePath build/Beaver.xcarchive archive CODE_SIGNING_ALLOWED=NO

app="build/Beaver.xcarchive/Products/Applications/Beaver.app"
[ -d "$app" ] || { echo "archive missing $app" >&2; exit 1; }

mkdir -p build/Payload
cp -R "$app" build/Payload/
(cd build && zip -qry Beaver.ipa Payload)
rm -rf build/Payload
echo "Wrote build/Beaver.ipa"
