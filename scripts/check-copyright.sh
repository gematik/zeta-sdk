#!/bin/sh

set -eu

COPYRIGHT_REGEX='^[[:space:]]*\*[[:space:]]\(C\) EY Strategy & Transactions GmbH, 2025, licensed for gematik GmbH[[:space:]]*$'
ROOT_DIR="${1:-.}"

MISSING_FILE=$(mktemp)
trap 'rm -f "$MISSING_FILE"' EXIT

echo "Checking copyright headers in ${ROOT_DIR} ..."
echo "Required: ${COPYRIGHT_REGEX}"
echo

find "$ROOT_DIR" \
  \( -type d \( \
      -name 'ktor-client-curl' -o \
      -name 'build' -o \
      -name '.gradle' -o \
      -name 'obj' -o \
      -name 'bin' -o \
      -name 'libs' -o \
      -name '.git' \
  \) -prune \) -o \
  \( -type f \( \
      -name '*.kt' -o \
      -name '*.java' -o \
      -name '*.cpp' -o \
      -name '*.cs' -o \
      -name '*.h' -o \
      -name '*.hpp' -o \
      -name '*.cc' \
  \) -print \) |
while IFS= read -r f; do
    if ! head -n 40 "$f" | grep -qE "$COPYRIGHT_REGEX"; then
        echo "$f"
    fi
done > "$MISSING_FILE"

COUNT=$(wc -l < "$MISSING_FILE" | tr -d ' ')

if [ "$COUNT" -eq 0 ]; then
    echo "OK: all scanned files contain the required copyright header."
    exit 0
fi

echo "FAIL: ${COUNT} file(s) missing or with an incorrect copyright header:"
echo
cat "$MISSING_FILE"
echo
echo "Expected:"
echo "$REQUIRED_TEXT"

exit
