#!/usr/bin/env bash
# Install FluxIt's repo-tracked git hooks (Phase 01 §8.5).
#
# Run once per fresh clone:   scripts/install-hooks.sh
# Hooks live in .githooks/ and are versioned with the repo. This script
# points `core.hooksPath` at that directory so git invokes them.
#
# Uninstall:  git config --unset core.hooksPath

set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

git config core.hooksPath .githooks
chmod +x .githooks/*

echo "[install-hooks] core.hooksPath -> .githooks"
echo "[install-hooks] Installed: $(ls .githooks | tr '\n' ' ')"
