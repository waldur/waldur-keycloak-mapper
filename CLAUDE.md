# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

A Java-based Keycloak plugin providing custom OIDC protocol mappers that integrate with Waldur (cloud platform). The build produces a shaded JAR that operators drop into Keycloak's `/opt/keycloak/providers/` directory; Keycloak picks it up via SPI on next start.

## Development commands

```bash
mvn clean install            # compile, run unit tests, build shaded JAR (skips ITs)
mvn -B verify                # also runs integration tests (requires Docker)
mvn deploy -Drevision=latest -s ci_settings.xml   # CI-only; pushes to GitLab package registry
```

`pom.xml` declares `<revision>X.Y.Z</revision>` as a fallback. CI overrides it via `mvn -Drevision=$CI_COMMIT_TAG` at deploy time. The release script bumps this property to keep local builds in sync.

## Architecture

All classes live in package `org.waldur.keycloak.mapper`.

### Mappers

- **`WaldurOIDCMinIOMapper`** — emits a comma-separated list of `scope_uuid`s as a custom claim, intended for MinIO policy mapping. Supports `customer` and `project` scope types.
- **`WaldurOIDCOfferingAccessMapper`** — checks per-user offering access against Waldur and **mutates Keycloak state** during token issuance: joins/leaves a configured group, grants/revokes a configured role. The mapper has no TLS-validation flag; it always uses strict TLS.
- **`WaldurOIDCOfferingUserUsernameMapper`** — emits the per-offering preferred username from Waldur as a custom claim.

All three are registered via `src/main/resources/META-INF/services/org.keycloak.protocol.ProtocolMapper` (fully-qualified class names).

### Shared HTTP client

`WaldurHttpClient` (package-private) wraps the JDK `HttpClient` with: 5s connect / 15s request timeouts, optional trust-all SSL context (for the TLS-validation toggle), and the `Authorization: Token <token>` header. Each mapper constructs one and calls `.get(url)`.

### DTOs

- `UserPermissionDTO` (used by MinIO mapper)
- `OfferingUserDTO` (used by username mapper)
- `UserHasAccessDTO` (used by offering access mapper)

## Tests

- **Unit tests** under `src/test/java/org/waldur/keycloak/mapper/UrlBuildingTest.java` — verify URL construction and special-character encoding for each endpoint. Run via `mvn test`.
- **Integration test** at `src/test/java/KeycloakProviderIT.java` — boots a Keycloak container with the freshly-built shaded JAR mounted as a provider, queries `/admin/serverinfo`, asserts all three mappers register. Runs via `mvn verify`. Auto-skips when Docker isn't available (`@Testcontainers(disabledWithoutDocker = true)`).

The IT image registry is configurable via the `docker.registry.prefix` Maven property (default: `registry.hpc.ut.ee/mirror/`, the same mirror used elsewhere in the Waldur stack).

## Key dependencies

- Keycloak 26.6.1 (provided)
- Jackson (transitive, via `keycloak-services`) for JSON
- JDK `java.net.http.HttpClient` for outbound HTTP — no Apache HttpClient
- Java 17 (Maven `<source>17</source><target>17</target>`)
- JUnit 5, Testcontainers, `dasniko/testcontainers-keycloak` (test scope)

## CI/CD

GitLab CI; the canonical jobs:

- **Build JAR** — `mvn clean install -DskipITs`, runs surefire unit tests, uploads JUnit XML.
- **Integration tests** — runs in a `maven:3.9-eclipse-temurin-17` image with `registry.hpc.ut.ee/mirror/library/docker:27-dind` as a service (DinD with `--mtu=1400`). Mirrors the Waldur DinD pattern from `waldur-pipelines`. Runs `mvn verify -Dsurefire.skip=true` so only failsafe runs; uploads failsafe XML.
- **Scan dependencies** — uses the shared `waldur-pipelines` `vulnerability-scan.yml` template with `osv-scanner`. Local override passes `--no-resolve` so the Maven extractor doesn't try to resolve our project's own artifact via deps.dev (it isn't on Maven Central).
- **Deploy latest JAR** — runs on `master` only, deploys with `-Drevision=latest`.
- **Release artifact** — runs on tag, builds with `-Drevision=$CI_COMMIT_TAG`, creates a GitHub release on `waldur/waldur-keycloak-mapper`.

Maven dependency cache is keyed on `pom.xml` hash so cold pipelines don't re-download ~80 MB.

## Release flow

Developer-driven, not CI-automated:

```bash
./scripts/release.sh 1.4.0           # bumps <revision>, generates CHANGELOG entry, commits, tags
git push origin master --tags        # CI picks up the tag and creates the GitHub release
```

`scripts/release.sh` orchestrates `bump_pom.py` and `changelog.sh`. The latter calls `claude --print` with a prompt template under `scripts/prompts/` to draft the markdown entry, with an interactive accept/edit/regenerate loop.

`scripts/backfill_changelog.sh` is a one-shot tool that walked all 14 historical tags to seed `CHANGELOG.md`. Iterates by `creatordate` (refname sort puts `v1.0.1` last because of the `v` prefix). Re-run with `--force` to regenerate.

Tag format going forward: bare `X.Y.Z`. The single existing `v1.0.1` outlier is preserved in history.

## Gotchas

- **Trailing slash required on `Waldur API URL`** mapper config — endpoint paths are concatenated, not joined.
- **TLS validation toggle** (on MinIO and Username mappers) disables BOTH hostname and cert-chain verification when off — not just hostname.
- **Group path semantics**: `WaldurOIDCOfferingAccessMapper` configures a group **name**; the mapper builds a path `/<name>` internally. Nested groups aren't supported by this mapper as configured.
- **Side effects in mapper**: `WaldurOIDCOfferingAccessMapper` mutates user-group and user-role state during token issuance. That's surprising for an OIDC mapper; flag if reviewing related code.
- **`provided` scope**: `keycloak-services` is provided by the Keycloak runtime, not bundled in the shaded JAR. CVEs against `keycloak-services` are operator's responsibility (upgrade Keycloak), not ours.
