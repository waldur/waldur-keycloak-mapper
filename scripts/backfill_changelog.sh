#!/bin/bash
# One-shot backfill: walk every git tag in semver order and synthesize a
# CHANGELOG.md entry per tag, plus an "Unreleased" entry for commits past
# the latest tag.
#
# Usage:
#   scripts/backfill_changelog.sh                # interactive (one prompt per entry)
#   scripts/backfill_changelog.sh --yes          # skip per-entry confirmation
#   scripts/backfill_changelog.sh --from 1.0.5   # start at this tag (inclusive)
#   scripts/backfill_changelog.sh --to 1.1.3     # stop at this tag (inclusive)
#   scripts/backfill_changelog.sh --dry-run      # print plan without writing
#   scripts/backfill_changelog.sh --force        # overwrite existing CHANGELOG.md
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CHANGELOG="$PROJECT_DIR/CHANGELOG.md"
DEFAULT_BRANCH="${DEFAULT_BRANCH:-master}"

YES=false
FROM=""
TO=""
DRY_RUN=false
FORCE=false

while [ $# -gt 0 ]; do
    case $1 in
        --yes|-y) YES=true; shift ;;
        --from) FROM=$2; shift 2 ;;
        --to) TO=$2; shift 2 ;;
        --dry-run) DRY_RUN=true; shift ;;
        --force) FORCE=true; shift ;;
        -h|--help)
            sed -n '2,12p' "$0"
            exit 0 ;;
        *) echo "Unknown flag: $1"; exit 1 ;;
    esac
done

cd "$PROJECT_DIR"

if [ -f "$CHANGELOG" ] && [ "$FORCE" != "true" ] && [ "$DRY_RUN" != "true" ]; then
    echo "Error: $CHANGELOG already exists. Pass --force to overwrite."
    exit 1
fi

if ! command -v claude >/dev/null 2>&1; then
    echo "Error: 'claude' CLI is not on PATH."
    exit 1
fi

# ── Build ordered tag list (oldest first) ─────────────────────────────────
# Sort by creator date, not refname: this repo has a "v1.0.1" tag which sorts
# AFTER bare "1.x.y" tags by version-sort semantics but is actually the oldest
# release chronologically.
TAGS=()
while IFS= read -r line; do
    TAGS+=("$line")
done < <(git for-each-ref --sort=creatordate --format='%(refname:short)' refs/tags)
if [ "${#TAGS[@]}" -eq 0 ]; then
    echo "Error: no tags found."
    exit 1
fi

# Apply --from / --to filters if set.
if [ -n "$FROM" ]; then
    FILTERED=()
    started=false
    for t in "${TAGS[@]}"; do
        [ "$t" = "$FROM" ] && started=true
        $started && FILTERED+=("$t")
    done
    TAGS=("${FILTERED[@]}")
fi
if [ -n "$TO" ]; then
    FILTERED=()
    for t in "${TAGS[@]}"; do
        FILTERED+=("$t")
        [ "$t" = "$TO" ] && break
    done
    TAGS=("${FILTERED[@]}")
fi

# Synthesize a "first commit" sentinel as the predecessor of the oldest tag.
FIRST_COMMIT=$(git rev-list --max-parents=0 HEAD | tail -n 1)

PROMPT=$(cat "$SCRIPT_DIR/prompts/changelog-prompt-backfill.md")
TMP_DIR=$(mktemp -d)
trap 'rm -rf "$TMP_DIR"' EXIT

ENTRIES=()

generate_entry() {
    local current=$1
    local previous=$2
    local label=${3:-$current}   # display label (strips leading 'v' if present)
    local date_str=$4
    local prompt_template=$5

    local data="$TMP_DIR/data-$label.json"
    local entry="$TMP_DIR/entry-$label.md"

    python3 "$SCRIPT_DIR/generate_changelog_data.py" \
        --quiet --date "$date_str" "$current" "$previous" > "$data"

    local total
    total=$(python3 -c "import json,sys; print(json.load(sys.stdin)['summary_stats']['total_commits'])" < "$data")

    if [ "$total" -eq 0 ]; then
        echo "  $label: 0 commits, skipping"
        return
    fi

    echo "  $label: $total commits, generating entry..."

    local full="${prompt_template//\{VERSION\}/$label}"
    full="${full//\{PREV_VERSION\}/$previous}"
    full="${full//\{DATE\}/$date_str}"

    if [ "$DRY_RUN" = "true" ]; then
        echo "    [dry-run] would call claude with prompt + $total commits"
        return
    fi

    printf '%s\n\n```json\n%s\n```\n' "$full" "$(cat "$data")" | \
        env -u CLAUDECODE claude --print > "$entry"

    if [ "$YES" != "true" ]; then
        echo ""
        echo "--- $label ---"
        cat "$entry"
        echo "---"
        read -rp "Accept? [y/edit/skip/quit] " ch
        case $ch in
            y|Y) ;;
            edit|e) ${EDITOR:-vim} "$entry" ;;
            skip|s) return ;;
            *) echo "Aborted."; exit 1 ;;
        esac
    fi

    ENTRIES+=("$entry")
}

normalize_label() {
    # Drop leading 'v' for the display heading; keep the actual ref unchanged.
    echo "$1" | sed 's/^v//'
}

# ── Iterate tags oldest -> newest, then Unreleased ────────────────────────
echo "=== Backfilling changelog for ${#TAGS[@]} tag(s) ==="
echo ""

prev=$FIRST_COMMIT
for tag in "${TAGS[@]}"; do
    label=$(normalize_label "$tag")
    tag_date=$(git log -1 --format=%ad --date=short "$tag")
    generate_entry "$tag" "$prev" "$label" "$tag_date" "$PROMPT"
    prev=$tag
done

# ── Unreleased section: commits since the latest tag ──────────────────────
LATEST_TAG="${TAGS[$((${#TAGS[@]}-1))]}"
UNRELEASED_COUNT=$(git rev-list --count --no-merges "${LATEST_TAG}..HEAD")
if [ "$UNRELEASED_COUNT" -gt 0 ]; then
    today=$(date +%Y-%m-%d)
    generate_entry "HEAD" "$LATEST_TAG" "Unreleased" "$today" "$PROMPT"
fi

if [ "$DRY_RUN" = "true" ]; then
    echo ""
    echo "Dry run complete. ${#TAGS[@]} tag(s) inspected."
    exit 0
fi

# ── Assemble CHANGELOG.md (newest first) ──────────────────────────────────
echo ""
echo "Assembling CHANGELOG.md (${#ENTRIES[@]} entries)..."

{
    echo "# Changelog"
    echo ""
    # Iterate ENTRIES in reverse so newest is on top.
    for ((i=${#ENTRIES[@]}-1; i>=0; i--)); do
        cat "${ENTRIES[$i]}"
        echo ""
    done
} > "$CHANGELOG"

echo "  $CHANGELOG written."
echo ""
echo "Review and commit:"
echo "  git add CHANGELOG.md && git commit -m 'Add CHANGELOG.md (backfilled)'"
