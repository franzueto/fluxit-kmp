#!/usr/bin/env bash
# Phase 01 §7 — iOS smoke build.
# Assembles the shared XCFramework, regenerates ios-app/FluxIt.xcodeproj from
# project.yml (via xcodegen), and runs xcodebuild against the iOS Simulator SDK.
#
# Usage: scripts/build-ios.sh
# Requires: macOS, Xcode, xcodegen (brew install xcodegen).

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

echo "[build-ios] Regenerating design tokens (ADR-005) + icons (ADR-005a)…"
./gradlew :core:core-designsystem:generateTokens :core:core-designsystem:generateIcons

echo "[build-ios] Assembling Shared.xcframework (release)…"
./gradlew :shared:state:assembleSharedReleaseXCFramework

echo "[build-ios] Regenerating ios-app/FluxIt.xcodeproj from project.yml…"
(cd ios-app && xcodegen generate)

echo "[build-ios] Building FluxIt for iOS Simulator (generic destination)…"

xcodebuild \
  -project ios-app/FluxIt.xcodeproj \
  -scheme FluxIt \
  -sdk iphonesimulator \
  -destination "generic/platform=iOS Simulator" \
  -configuration Debug \
  build \
  CODE_SIGN_IDENTITY="" \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGNING_ALLOWED=NO

echo "[build-ios] OK"
