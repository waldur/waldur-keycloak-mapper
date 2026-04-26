#!/bin/bash
# Generate a single changelog entry for VERSION and prepend it to CHANGELOG.md.
#
# Usage:
#   scripts/changelog.sh <VERSION>
#
# Honours $IS_RC=true to apply RC-specific behavior (skip prior RC entries on
# stable promotion, drop Highlights/Statistics in the prompt).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CHANGELOG="$PROJECT_DIR/CHANGELOG.md"
TMP_ENTRY="/tmp/waldur-keycloak-mapper-changelog-entry.md"
TMP_DATA="/tmp/waldur-keycloak-mapper-release-data.json"

if [ $# -lt 1 ]; then
    echo "Usage: $0 <VERSION>"
    exit 1
fi

VERSION=$1
DATE=$(date +%Y-%m-%d)

if ! command -v claude >/dev/null 2>&1; then
    echo "Error: 'claude' CLI is not on PATH."
    exit 1
fi

IS_RC="${IS_RC:-false}"
BASE_VERSION=$(echo "$VERSION" | sed 's/-rc\.[0-9]*$//')

# ── Determine previous version ────────────────────────────────────────────
if [ -f "$CHANGELOG" ]; then
    if [ "$IS_RC" = "true" ]; then
        PREV_TAG=$(grep "^## " "$CHANGELOG" | grep -v "\-rc\." | head -n 1 | sed 's/^## \([^ ]*\).*/\1/')
    else
        PREV_TAG=$(grep -m 1 "^## " "$CHANGELOG" | sed 's/^## \([^ ]*\).*/\1/')
    fi
fi

if [ -z "${PREV_TAG:-}" ] || [ "${PREV_TAG:-}" = "Unreleased" ]; then
    if [ "$IS_RC" = "true" ]; then
        PREV_TAG=$(git -C "$PROJECT_DIR" tag --sort=-v:refname | grep -v "\-rc\." | head -n 1)
    else
        PREV_TAG=$(git -C "$PROJECT_DIR" tag --sort=-v:refname | head -n 1)
    fi
fi

if [ -z "${PREV_TAG:-}" ]; then
    echo "Error: could not determine previous version (no CHANGELOG.md header and no git tags)."
    exit 1
fi

echo "=== Changelog: $VERSION (since $PREV_TAG) ==="
echo ""

# ── Step 1: Collect commit data ───────────────────────────────────────────
echo "[1/3] Collecting commit data..."
python3 "$SCRIPT_DIR/generate_changelog_data.py" "$VERSION" "$PREV_TAG" > "$TMP_DATA"

TOTAL=$(python3 -c "import json,sys; print(json.load(sys.stdin)['summary_stats']['total_commits'])" < "$TMP_DATA")
echo "  Found $TOTAL commits."

if [ "$TOTAL" -eq 0 ]; then
    echo "No commits between $PREV_TAG and $VERSION. Nothing to do."
    exit 0
fi

# ── Step 2: Generate via Claude ───────────────────────────────────────────
echo ""
echo "[2/3] Generating changelog with Claude..."

PROMPT_TEMPLATE=$(cat "$SCRIPT_DIR/prompts/changelog-prompt.md")
FULL_PROMPT="${PROMPT_TEMPLATE//\{VERSION\}/$VERSION}"
FULL_PROMPT="${FULL_PROMPT//\{PREV_VERSION\}/$PREV_TAG}"
FULL_PROMPT="${FULL_PROMPT//\{DATE\}/$DATE}"

generate() {
    printf '%s\n\n```json\n%s\n```\n' "$FULL_PROMPT" "$(cat "$TMP_DATA")" | \
        env -u CLAUDECODE claude --print > "$TMP_ENTRY"
}
generate

echo ""
echo "=== Generated entry ==="
echo ""
cat "$TMP_ENTRY"
echo ""
echo "======================="
echo ""
read -rp "Accept this changelog? [y/edit/regenerate/quit] " choice

case $choice in
    y|Y|yes) ;;
    edit|e) ${EDITOR:-vim} "$TMP_ENTRY" ;;
    regenerate|r)
        echo "Regenerating..."
        generate
        echo ""
        cat "$TMP_ENTRY"
        echo ""
        read -rp "Accept now? [y/edit/quit] " choice2
        case $choice2 in
            y|Y) ;;
            edit|e) ${EDITOR:-vim} "$TMP_ENTRY" ;;
            *) echo "Aborted."; exit 1 ;;
        esac
        ;;
    *) echo "Aborted."; exit 1 ;;
esac

# ── Step 3: Update CHANGELOG.md ───────────────────────────────────────────
echo ""
echo "[3/3] Updating CHANGELOG.md..."

if [ -f "$CHANGELOG" ]; then
    if [ "$IS_RC" = "true" ]; then
        python3 - <<EOF
import re
base = "$BASE_VERSION"
out = []
skip = False
for line in open("$CHANGELOG"):
    if re.match(r"^## " + re.escape(base) + r"-rc\.\d+", line):
        skip = True
        continue
    if skip and re.match(r"^## ", line):
        skip = False
    if not skip:
        out.append(line)
open("$CHANGELOG", "w").writelines(out)
EOF
    fi
    {
        echo "# Changelog"
        echo ""
        cat "$TMP_ENTRY"
        echo ""
        tail -n +3 "$CHANGELOG"   # drop the existing "# Changelog\n" header
    } > /tmp/waldur-keycloak-mapper-final-changelog.md
    mv /tmp/waldur-keycloak-mapper-final-changelog.md "$CHANGELOG"
else
    {
        echo "# Changelog"
        echo ""
        cat "$TMP_ENTRY"
        echo ""
    } > "$CHANGELOG"
fi

echo "  CHANGELOG.md updated."
