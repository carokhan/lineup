#!/usr/bin/env bash
# Run the real on-device ML Kit OCR over local image files and print the layout as TSV.
#
#   tools/ocr.sh poster.png [more.png ...]
#
# Emits "<left>,<top>,<right>,<bottom>\t<text>" per line, plus the parser's verdict.
# Requires an adb-connected device with the debug build installed.
set -euo pipefail

PKG=com.lineup.app
ADB="${ADB:-adb}"

for img in "$@"; do
  base="$(basename "$img")"
  "$ADB" push -q "$img" "/data/local/tmp/$base" >/dev/null 2>&1 || "$ADB" push "$img" "/data/local/tmp/$base" >/dev/null
  "$ADB" shell "run-as $PKG cp /data/local/tmp/$base files/$base"
  "$ADB" shell am force-stop "$PKG" >/dev/null
  "$ADB" logcat -c
  "$ADB" shell am start -n "$PKG/.ui.MainActivity" -e ocr_file "$base" >/dev/null

  for _ in $(seq 1 160); do
    if "$ADB" logcat -d -s LineupOcr 2>/dev/null | grep -q -E "DRAFT|FAILED|MISSING"; then break; fi
    sleep 0.25
  done

  echo "=== $base ==="
  "$ADB" logcat -d -s LineupOcr 2>/dev/null \
    | sed 's/^.*LineupOcr *: //' \
    | grep -E '^(LINE|PICK|PARSE|FAILED|MISSING|BEGIN|CHOSEN|DRAFT)' \
    | sed 's/^LINE\t//'
  "$ADB" shell "run-as $PKG rm -f files/$base"
  "$ADB" shell "rm -f /data/local/tmp/$base"
done
