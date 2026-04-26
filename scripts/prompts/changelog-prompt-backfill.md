You are generating a HISTORICAL changelog entry for waldur-keycloak-mapper version {VERSION} (previous: {PREV_VERSION}, date: {DATE}).

The audience is **Keycloak operators** integrating Waldur identity/role mapping. Frame changes in terms of operator-visible impact.

This is an old release — keep it short and factual. Output ONLY the markdown entry, no preamble or code fences.

## Output format

Start with exactly:

```
## {VERSION} - {DATE}
```

Then:

- If there are **fewer than 3 commits**: drop Highlights and Statistics. Produce a single flat bulleted list of changes.
- If there are **3 or more commits**: a 1-2 sentence Highlights paragraph, then a flat bulleted list grouped under sensible subsections (`**What's New**`, `**Bug Fixes**`, `**Security**`, etc.) where the grouping is obvious. Skip the subsection structure if the changes don't naturally split.
- Always end the entry with a `---` separator line.

## Rules

1. **Be concise**: many old tags have 1-3 commits; do not pad. Aim for 5-15 lines total.
2. **Skip noise**: drop merge commits, version-bump-only commits, pure CI/lint changes unless that's literally all there is.
3. **Keycloak version bumps**: phrase as "Keycloak X.Y.Z support".
4. **Preserve Jira keys**: keep `[WAL-####]` in parens at the end of the relevant bullet.
5. **No invention**: describe only what's in the commit data.
6. **Sentence case**: capital letter, terminal period.
7. **If the previous tag is several minor versions behind** (e.g. 1.1.3 → 1.3.4 with no intermediate tags in between), open Highlights with: "This release consolidates work that was deployed without intermediate tags." then summarize the major themes.

Here is the commit data:
