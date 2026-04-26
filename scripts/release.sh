#!/bin/bash
# Release orchestrator for waldur-keycloak-mapper.
#
# Usage:
#   scripts/release.sh <VERSION>
#
# Validates the version, bumps <revision> in pom.xml, generates a changelog
# entry, commits everything, and tags. Push is left to the human:
#
#   git push origin master --tags
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DEFAULT_BRANCH="${DEFAULT_BRANCH:-master}"

if [ $# -lt 1 ]; then
    echo "Usage: $0 <VERSION>"
    echo "Example: $0 1.4.0  or  $0 1.4.0-rc.1"
    exit 1
fi

VERSION=$1

# ── Validate version format ───────────────────────────────────────────────
if ! echo "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+(-rc\.[0-9]+)?$'; then
    echo "Error: '$VERSION' must be X.Y.Z or X.Y.Z-rc.N (no 'v' prefix)."
    exit 1
fi

# ── RC detection ──────────────────────────────────────────────────────────
IS_RC=false
if echo "$VERSION" | grep -qE '\-rc\.[0-9]+$'; then
    IS_RC=true
fi
export IS_RC

# ── Pre-flight checks ────────────────────────────────────────────────────
cd "$PROJECT_DIR"

BRANCH=$(git branch --show-current)
if [ "$BRANCH" != "$DEFAULT_BRANCH" ]; then
    echo "Warning: you are on branch '$BRANCH', not '$DEFAULT_BRANCH'."
    read -rp "Continue anyway? [y/N] " choice
    [ "$choice" = "y" ] || [ "$choice" = "Y" ] || exit 1
fi

if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "Error: working tree is not clean. Commit or stash changes first."
    exit 1
fi

if git rev-parse "$VERSION" >/dev/null 2>&1; then
    echo "Error: tag '$VERSION' already exists."
    exit 1
fi

echo "=== Releasing waldur-keycloak-mapper $VERSION ==="
echo ""

# ── Step 1: Bump pom.xml ──────────────────────────────────────────────────
echo "[1/4] Bumping pom.xml <revision>..."
python3 "$SCRIPT_DIR/bump_pom.py" "$VERSION"
echo ""

# ── Step 2: Generate changelog ────────────────────────────────────────────
echo "[2/4] Generating changelog..."
"$SCRIPT_DIR/changelog.sh" "$VERSION"
echo ""

# ── Step 3: Commit ────────────────────────────────────────────────────────
echo "[3/4] Committing release..."
git add pom.xml CHANGELOG.md
git commit -m "Release $VERSION"
echo ""

# ── Step 4: Tag ───────────────────────────────────────────────────────────
echo "[4/4] Tagging $VERSION..."
git tag "$VERSION"
echo ""

echo "=== Release $VERSION prepared ==="
echo ""
echo "Review the commit and tag, then push with:"
echo "  git push origin $DEFAULT_BRANCH --tags"
