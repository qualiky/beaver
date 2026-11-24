#!/usr/bin/env bash
# Fails if android/core/src/main imports anything outside the pure-Kotlin allowlist:
# kotlin.*, kotlinx.serialization.*, java.time.*, java.math.*, java.util.*
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
src="$root/android/core/src/main"
status=0

while IFS= read -r file; do
  while IFS= read -r line; do
    imp="$(printf '%s' "$line" | sed -E 's/^[[:space:]]*import[[:space:]]+//; s/[[:space:]]+as[[:space:]]+.*$//; s/[[:space:];]+$//')"
    case "$imp" in
      kotlin.*|kotlinx.serialization.*|java.time.*|java.math.*|java.util.*) ;;
      *)
        echo "Disallowed import in ${file#"$root"/}: $imp" >&2
        status=1
        ;;
    esac
  done < <(grep -E '^[[:space:]]*import[[:space:]]' "$file" || true)
done < <(find "$src" -name '*.kt' -type f)

if [ "$status" -eq 0 ]; then
  echo "OK"
fi
exit "$status"
