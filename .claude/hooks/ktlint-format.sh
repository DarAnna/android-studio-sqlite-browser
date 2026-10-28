#!/usr/bin/env bash
# PostToolUse hook: formats a Kotlin file with ktlint right after Claude edits it.
# Rules that ktlint can't fix go back to Claude (exit 2) so it fixes them itself.
set -uo pipefail

file=$(jq -r '.tool_input.file_path // empty')
case "$file" in
  *.kt | *.kts) ;;
  *) exit 0 ;;
esac
[ -f "$file" ] || exit 0

if ! command -v ktlint >/dev/null; then
  echo "ktlint not installed; skipping format (brew install ktlint)" >&2
  exit 0
fi

# Run from the repo root so ktlint picks up .editorconfig.
cd "$(dirname "$0")/../.." || exit 0
if ! output=$(ktlint --format --relative "$file" 2>&1); then
  echo "ktlint could not fix everything in $file:" >&2
  echo "$output" >&2
  exit 2
fi
