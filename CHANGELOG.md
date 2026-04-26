# Changelog

## 1.4.0 - 2026-04-26

### Highlights

This is the first release on the new versioning scheme since v1.0.1, bundling roughly two years of accumulated work. Operators get Keycloak 26.6.1 support, two additional protocol mappers (offering access with group/role provisioning, and per-offering preferred username), and a hardened build pipeline with vulnerability scanning, integration tests, and proper URL encoding throughout.

### What's New

- Keycloak 26.6.1 support, with the build now producing a shaded JAR that drops directly into `/opt/keycloak/providers/`.
- New `WaldurOIDCOfferingAccessMapper` that checks per-user offering access against Waldur and provisions Keycloak group membership and role assignments during token issuance, with configurable claim name, visibility, and username source.
- New `WaldurOIDCOfferingUserUsernameMapper` that emits the per-offering preferred username from Waldur as a custom claim.
- `WaldurOIDCMinIOMapper` now emits comma-separated `scope_uuid`s suitable for MinIO policy mapping, covering both customer and project scopes.
- Custom claim name and claim visibility (ID token, access token, userinfo) are now configurable per mapper ([WAL-5781]).
- Optional TLS validation toggle and `fail_on_error` flag on applicable mappers ([WAL-5781]).
- Release artifacts are now published to GitHub on tag in addition to the GitLab package registry.

### Improvements

- Updated user-permissions integration in the MinIO mapper to match the current Waldur API contract ([WAL-8745]).
- Migrated configuration to typed `ProviderConfigProperty` declarations for clearer admin UI hints.
- Code reorganized under `org.waldur.keycloak.mapper`, with a shared `WaldurHttpClient` (5s connect / 15s request timeouts, optional trust-all SSL, token auth) backing all three mappers.

### Bug Fixes

- Correct URL encoding of special characters in all Waldur endpoint URLs.
- Fixed claim emission bug surfaced during the 26.6.0 upgrade.
- Fixed role check in the offering access mapper.
- Fixed `scopeUuid` field type and default property values in offering access configuration.

### Security

- New CI job scans dependencies for known vulnerabilities via `osv-scanner` on every pipeline.
- New integration test boots Keycloak with the freshly built JAR mounted as a provider and verifies all three mappers register, catching SPI breakage before release.

### Statistics

> 36 commits, 23 files changed (+2249/−256 lines)

---

## Unreleased - 2026-04-26

Highlights: Keycloak 26.6.0 support lands alongside a claim-handling bug fix, with the codebase moved into a real `org.waldur.keycloak.mapper` package and a shared HTTP client. CI now runs an integration test that loads the built JAR into Keycloak and scans dependencies for known vulnerabilities.

**What's New**
- Keycloak 26.6.0 support.
- Integration test that loads the built JAR as a Keycloak provider and verifies registration.

**Bug Fixes**
- Fixed claim handling bug surfaced during the 26.6.0 upgrade.
- Post-upgrade cleanup with URL encoding fixes and added unit tests for URL building.

**Refactor**
- Moved sources into `org.waldur.keycloak.mapper` package and introduced a shared `WaldurHttpClient` across mappers; update any deployment that referenced the old default-package classes.

**Security**
- CI now scans dependencies for known vulnerabilities.

**CI / Docs**
- Maven dependencies cached in CI with quieter download output.
- Inverted documentation publishing flow (WAL-9140).
- README and licence documentation updates.

---

## 1.3.4 - 2025-05-22

- Corrected the `scopeUuid` field type in `UserPermissionDTO` to ensure proper deserialization of Waldur permission payloads.

---

## 1.1.3 - 2025-05-22

- Updated user permissions integration in the MinIO mapper, affecting how Waldur permissions are consumed for policy claims (WAL-8745).
- Fixed role check in the offering access mapper to ensure correct group/role assignment.

---

## 1.1.2 - 2024-08-30

- Added configurable username source for the offering access mapper.

---

## 1.1.1 - 2024-08-30

- Removed offering user check and provider UUID property from the offering access mapper.
- Fixed default property values in the offering access mapper.

---

## 1.1.0 - 2024-08-29

- Added support for role assignment in the offering access mapper.
- Added claim setting configuration to the offering access mapper.

---

## 1.0.9 - 2024-08-28

- Renamed target class in the SPI ProtocolMapper service registration.

---

## 1.0.8 - 2024-08-28

Expands the mapper suite with new custom OIDC mappers for Waldur permissions and offering users, alongside refinements to group mapping behavior.

**What's New**
- Added custom mappers for MinIO policies, offering user usernames, and group membership, with supporting permission DTOs and SPI registration.

**Improvements**
- Improved the group mapper and offering access mapper logic.

**Bug Fixes**
- Fixed linter issues in the README.

---

## 1.0.7 - 2023-08-13

Internal refactor of the OIDC protocol mapper alongside release-pipeline improvements that publish JAR artifacts to GitHub.

**What's New**
- JAR artifacts are now published to GitHub on release.
- Added missing CI dependencies for the build pipeline.

**Improvements**
- Refactored mapper configuration to use typed `ProviderConfigProperty` values.
- Consolidated three separate `setClaim` overrides into a single common implementation.

---

## 1.0.6 - 2023-06-01

Adds operator-tunable resilience controls for the Waldur protocol mapper, allowing TLS verification and error handling to be configured per deployment.

- Added optional TLS validation toggle and `fail_on_error` flag for the Waldur OIDC protocol mapper (WAL-5781).
- Updated documentation link in the README.
- Updated GitLab CI configuration.

---

## 1.0.5 - 2023-05-31

- Extended `WaldurOIDCProtocolMapper` to apply claim transformations to additional token types beyond the access token.

---

## 1.0.4 - 2023-05-30

- Restrict claim transformation to user info responses only in `WaldurOIDCProtocolMapper`.

---

## 1.0.3 - 2023-05-30

- Added configuration options to customize claim name and visibility in the Waldur OIDC protocol mapper (WAL-5781).
- Removed leftover code from `WaldurOIDCProtocolMapper`.

---

## 1.0.2 - 2023-05-30

Adds Waldur claims to the OIDC userinfo endpoint alongside minor build and documentation fixes.

**What's New**
- Waldur claim now included in the userinfo response, not just ID/access tokens.

**Bug Fixes**
- Corrected artifact download link in README.

**Build**
- Dropped `CI_COMMIT_TAG` validation in the GitLab CI pipeline.

---

## 1.0.1 - 2023-05-10

Initial release introducing the Waldur attribute extractor protocol mapper for Keycloak, along with setup documentation and release tooling.

**What's New**
- Added Waldur attribute extractor protocol mapper for Keycloak, registered via SPI to expose offering user attributes (WAL-5705).
- Added setup documentation in README covering plugin installation.

---

