#!/usr/bin/env bash
# Phase 05 §12/§15 — iOS SKIE bridging smoke test.
# Assembles the shared XCFramework, regenerates ios-app/FluxIt.xcodeproj from
# project.yml (via xcodegen), and runs the FluxItTests unit-test bundle against
# the iOS Simulator. Proves the shared MVI store surface bridges into Swift
# (sealed -> exhaustive enum, Flow -> AsyncSequence, dispatch callable).
#
# Usage: scripts/test-ios.sh [simulator-name]
#   simulator-name defaults to whatever iPhone simulator is available (resolved
#   dynamically so this works across Xcode versions / CI runners).
# Requires: macOS, Xcode, xcodegen (brew install xcodegen).

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

# Resolve a concrete simulator: honour an explicit arg, else pick the first
# available iPhone. A concrete device (not a generic destination) is required to
# actually run XCTest.
if [[ -n "${1:-}" ]]; then
  sim_name="$1"
else
  sim_name="$(xcrun simctl list devices available | grep -oE 'iPhone [0-9][^(]*' | head -1 | sed 's/[[:space:]]*$//')"
fi
if [[ -z "${sim_name:-}" ]]; then
  echo "[test-ios] No available iPhone simulator found." >&2
  exit 1
fi

echo "[test-ios] Regenerating design tokens (ADR-005) + icons (ADR-005a)…"
./gradlew :core:core-designsystem:generateTokens :core:core-designsystem:generateIcons

echo "[test-ios] Assembling Shared.xcframework (release)…"
./gradlew :shared:state:assembleSharedReleaseXCFramework

echo "[test-ios] Regenerating ios-app/FluxIt.xcodeproj from project.yml…"
(cd ios-app && xcodegen generate)

echo "[test-ios] Running FluxItTests on iOS Simulator ($sim_name)…"
xcodebuild test \
  -project ios-app/FluxIt.xcodeproj \
  -scheme FluxIt \
  -sdk iphonesimulator \
  -destination "platform=iOS Simulator,name=$sim_name" \
  -configuration Debug \
  CODE_SIGN_IDENTITY="" \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGNING_ALLOWED=NO

echo "[test-ios] OK"
