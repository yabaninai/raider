# Licensing

Raider is **MIT** (`LICENSE`). Owner constraint (2026-10-05): the project must
stay usable under MIT — copyleft must never enter the runtime dependency
graph, the source tree, or shipped artifacts.

## Policy

| Bucket | Licenses | Rule |
| --- | --- | --- |
| ALLOWED | MIT, BSD-2/3, Apache-2.0, ISC, CC0, Unlicense, public domain | May be added as runtime dependencies freely |
| FORBIDDEN | GPL, LGPL, AGPL (any version) | Never in the runtime graph, never bundled, never linked |
| REVIEW | MPL, EPL, CDDL | Fail the audit until explicitly allowlisted in `license_audit.py` `REVIEW_OK` with a dated comment |
| UNKNOWN | no pom license / exhausted parent chain | Fails the audit — an unaudited dependency is not a safe dependency |

- Build-time sbt plugins (scalafmt, scalafix, sbt-assembly) do not ship in
  artifacts; permissive-family is still preferred.
- Plugin ecosystem (roadmap Phase 4): the same policy applies to plugin
  runtime dependencies, enforced by the future `plugin` gate profile.
- Shipped artifacts (fat JAR) must include a generated `THIRD-PARTY-NOTICES`
  listing bundled libraries and their licenses (release checklist).

## Enforcement

The `license-audit` gate runs in the `fast` profile:

```sh
python3 scripts/quality/license_audit.py --self-test
```

It resolves the union runtime classpath (`export root/runtime:fullClasspath`),
finds each external jar's POM in the coursier cache (including parent-POM
license inheritance), classifies every declared license, and fails on
forbidden/review/unknown. Reports land in `artifacts/license-audit/`.

**Before adding any new dependency**: run the audit locally, confirm PASS, and
record the choice in the card's work record.

## Baseline audit — 2026-10-05

25 runtime artifacts: Apache-2.0 (Scala 3.9.0 toolchain, ZIO 2.1.26,
zio-json/zio-streams, izumi-reflect, magnolia, scala-collection-compat,
compiler-interface), BSD-3 (JLine 4.0.14 ×4, scala-asm/ASM), MIT (slf4j-api).
Problems: **0**. Report: `artifacts/license-audit/report-20261005-*.json`.

## Known exclusions

- [Lanterna](https://github.com/mabe02/lanterna) (TUI toolkit) — LGPL-3.0,
  permanently excluded; see [ADR-016](adr/ADR-016-tui-renderer.md) for the
  MIT-clean renderer decision.
