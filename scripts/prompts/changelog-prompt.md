You are generating a changelog entry for waldur-keycloak-mapper version {VERSION} (previous: {PREV_VERSION}, date: {DATE}).

The audience is **Keycloak operators** integrating Waldur identity/role mapping into their realms. Frame changes in terms of what they can do or what got safer/more reliable, not internal refactors.

Output ONLY the changelog entry in markdown. Do not include any preamble, explanation, or code fences. The output will be inserted directly into CHANGELOG.md.

## Output format

Start with exactly this header:

```
## {VERSION} - {DATE}
```

Then include these sections in order, omitting any that are empty:

### 1. Highlights

A short paragraph (2-4 sentences) explaining WHY this release matters. Focus on user-visible impact. What can operators do now that they couldn't before? What got more reliable or more secure?

### 2. What's New / Improvements / Bug Fixes

Group changes by theme. Use subsections like:

- **What's New** — genuinely new capabilities
- **Improvements** — enhancements to existing features
- **Bug Fixes** — corrections (only if there are meaningful ones)
- **Security** — CVE fixes, vulnerability scan additions, hardening

Each item is a single bullet point in sentence case ending with a period.

### 3. Statistics

A brief summary line:

> N commits, M files changed (+A/−R lines)

End the entry with a `---` separator line.

## Rules

1. **Collapse revert pairs**: if a commit was reverted and re-applied, mention only the final state.
2. **Exclude noise**: skip version-bump commits, merge commits, trivial reformatting, and pure-CI commits unless the CI change has user-visible impact (e.g., new release artifact channel, new vulnerability scan).
3. **Keycloak version bumps**: phrase as "Keycloak X.Y.Z support" rather than "upgrade to Keycloak X.Y.Z".
4. **Preserve issue references**: keep Jira keys like `[WAL-9140]` in parentheses at the end of the relevant bullet point.
5. **Deduplicate**: if two commits address the same change (fix + follow-up fix), combine them into one bullet.
6. **No invented information**: only describe what you can see in the commit data. Do not speculate about behavior or motivation that isn't there.
7. **Keep it concise**: aim for 15-35 lines total. Group small related changes into single bullets.
8. **RC releases**: if `{VERSION}` ends in `-rc.N`, drop the Highlights section, drop the Statistics section, and produce a single flat list of changes.

Here is the commit data:
