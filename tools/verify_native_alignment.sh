#!/usr/bin/env bash
set -euo pipefail

INPUT="${1:?Usage: $0 <apk-or-aar>}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT
unzip -q "$INPUT" -d "$WORK_DIR"
READELF="${LLVM_READELF:-}"
if [[ -z "$READELF" ]]; then
  READELF="$(command -v llvm-readelf 2>/dev/null || true)"
fi
if [[ -z "$READELF" ]]; then
  SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [[ -n "$SDK_DIR" && -d "$SDK_DIR/ndk" ]]; then
    READELF="$(find "$SDK_DIR/ndk" -type f -name llvm-readelf -print -quit)"
  fi
fi
if [[ -z "$READELF" ]]; then
  echo "llvm-readelf not found; install the Android NDK or set LLVM_READELF." >&2
  exit 1
fi
if ! "$READELF" --version >/dev/null 2>&1; then
  echo "llvm-readelf at '$READELF' is not executable on this host." >&2
  exit 1
fi
libraries=()
while IFS= read -r -d '' library; do
  libraries+=("$library")
done < <(find "$WORK_DIR" -type f -name '*.so' -print0)
if [[ "${#libraries[@]}" -eq 0 ]]; then
  echo "No native libraries found" >&2
  exit 1
fi
for library in "${libraries[@]}"; do
  "$READELF" -l "$library" | awk -v file="$library" '
    /LOAD/ { print file ": " $0; if ($NF != "0x4000") bad=1 }
    END { if (bad) exit 1 }'
  "$READELF" -d "$library" | awk -v file="$library" '
    /HASH/ && !/GNU_HASH/ { found=1 }
    END {
      if (!found) {
        print file ": missing legacy DT_HASH required by Android API 21" > "/dev/stderr"
        exit 1
      }
    }'
done
if command -v zipalign >/dev/null 2>&1; then
  zipalign -c -P 16 4 "$INPUT"
else
  echo "zipalign not found; run zipalign -c -P 16 4 $INPUT separately" >&2
fi
