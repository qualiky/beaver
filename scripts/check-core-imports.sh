#!/usr/bin/env bash
# Fails if any file in BeaverCore sources imports anything other than Foundation.
set -euo pipefail
cd "$(dirname "$0")/.."
dir="Packages/BeaverCore/Sources"
[ -d "$dir" ] || { echo "missing $dir" >&2; exit 1; }
bad=$(grep -rnE '^[[:space:]]*(@[A-Za-z_]+[[:space:]]+)*import[[:space:]]+' "$dir" --include='*.swift' \
  | grep -vE 'import[[:space:]]+Foundation[[:space:]]*(//.*)?$' || true)
if [ -n "$bad" ]; then
  echo "BeaverCore may only import Foundation. Offending lines:" >&2
  echo "$bad" >&2
  exit 1
fi
echo "OK: BeaverCore imports only Foundation."
