#!/usr/bin/env python3
"""Collect commit data between two refs and emit JSON for changelog generation.

Usage:
    python3 scripts/generate_changelog_data.py <current_ref> <previous_ref>

If <current_ref> doesn't exist as a tag, HEAD is used. Emits structured JSON
on stdout that the prompt template feeds into the AI changelog generator.
"""

import argparse
import json
import re
import subprocess
import sys
from datetime import date


JIRA_KEY_RE = re.compile(r"\[((?:WAL|HPCMP)-\d+)\]")


def run(cmd, cwd=None):
    try:
        result = subprocess.run(
            cmd, shell=True, cwd=cwd, capture_output=True, text=True, check=True
        )
        return result.stdout.strip()
    except subprocess.CalledProcessError as e:
        if e.stderr:
            print(f"  stderr: {e.stderr.strip()}", file=sys.stderr)
        return ""


def get_repo_root():
    root = run("git rev-parse --show-toplevel")
    if not root:
        print("Error: not inside a git repository", file=sys.stderr)
        sys.exit(1)
    return root


def ref_exists(ref, cwd):
    try:
        subprocess.run(
            f"git rev-parse --verify {ref}",
            shell=True, cwd=cwd, capture_output=True, text=True, check=True,
        )
        return True
    except subprocess.CalledProcessError:
        return False


def resolve_ref(ref, cwd, *, quiet=False):
    if ref_exists(ref, cwd):
        return ref
    if not quiet:
        print(f"Ref '{ref}' not found, using HEAD instead", file=sys.stderr)
    return "HEAD"


# Category rules tuned for this repo's commit style: terse Title-Case verbs,
# Jira keys like [WAL-9140].
CATEGORY_RULES = [
    ("features", re.compile(r"^(Add |Implement |Support |Introduce |Allow |Enable )", re.IGNORECASE)),
    ("fixes", re.compile(r"^(Fix |Correct |Handle |Resolve )|[Bb]ug fix", re.IGNORECASE)),
    ("refactor", re.compile(r"^(Refactor|Move |Rename |Drop |Remove |Clean |Migrate |Improve |Simplify )", re.IGNORECASE)),
    ("chore", re.compile(r"^(Bump |Upgrade |Release |Update dependenc)|^[Uu]pgrade to", re.IGNORECASE)),
    ("ci", re.compile(r"^(CI[:\- ]|ci:)")),
    ("docs", re.compile(r"^(Update documentation|Document |docs:)", re.IGNORECASE)),
]


def categorize(subject, changed_files):
    for cat, pat in CATEGORY_RULES:
        if pat.search(subject):
            return cat
    if changed_files and all(
        f.startswith("docs/") or f.upper().startswith("README") for f in changed_files
    ):
        return "docs"
    return "other"


def collect_commits(prev, current, cwd):
    fmt = "%h|%s|%an|%ad|%b%x00"
    cmd = f"git log {prev}..{current} --pretty=format:'{fmt}' --date=short --no-merges"
    output = run(cmd, cwd=cwd)
    if not output:
        return []

    commits = []
    for entry in (e.strip() for e in output.split("\x00") if e.strip()):
        parts = entry.split("|", 4)
        if len(parts) < 4:
            continue
        commit_hash = parts[0]
        subject = parts[1]
        files = files_for(commit_hash, cwd)
        commits.append({
            "hash": commit_hash,
            "subject": subject,
            "author": parts[2],
            "date": parts[3],
            "body": (parts[4].strip()[:500] if len(parts) > 4 else ""),
            "changed_files": files,
            "category": categorize(subject, files),
            "jira_keys": JIRA_KEY_RE.findall(subject),
        })
    return commits


def files_for(commit_hash, cwd):
    output = run(f"git diff-tree --no-commit-id --name-only -r {commit_hash}", cwd=cwd)
    if not output:
        return []
    return [f for f in output.split("\n") if f.strip()]


def aggregate(prev, current, cwd):
    numstat = run(f"git diff --numstat {prev}..{current}", cwd=cwd)
    files_changed = added = removed = 0
    for line in numstat.split("\n"):
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) >= 3:
            files_changed += 1
            try:
                added += int(parts[0]) if parts[0] != "-" else 0
                removed += int(parts[1]) if parts[1] != "-" else 0
            except ValueError:
                pass
    return {"files_changed": files_changed, "lines_added": added, "lines_removed": removed}


def by_category(commits):
    cats = {}
    for c in commits:
        cats.setdefault(c["category"], []).append(c)
    return cats


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("current_ref")
    p.add_argument("previous_ref")
    p.add_argument("--date", help="Override release date (YYYY-MM-DD); defaults to today")
    p.add_argument("--quiet", action="store_true", help="Suppress fallback-to-HEAD warnings")
    args = p.parse_args()

    repo_root = get_repo_root()
    current = resolve_ref(args.current_ref, repo_root, quiet=args.quiet)
    previous = resolve_ref(args.previous_ref, repo_root, quiet=args.quiet)

    if not args.quiet:
        print(f"Collecting commits {previous}..{current} in {repo_root}", file=sys.stderr)

    commits = collect_commits(previous, current, repo_root)
    stats = aggregate(previous, current, repo_root)

    output = {
        "version": args.current_ref,
        "previous_version": args.previous_ref,
        "date": args.date or date.today().isoformat(),
        "summary_stats": {"total_commits": len(commits), **stats},
        "commits": commits,
        "categories": by_category(commits),
    }
    json.dump(output, sys.stdout, indent=2)
    print()


if __name__ == "__main__":
    main()
