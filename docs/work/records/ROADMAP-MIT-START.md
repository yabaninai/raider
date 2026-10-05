# ROADMAP-MIT-START: work record

Status: done. Date: 2026-10-05T16:12Z. Baseline commit/source: 3d3f5c7 (docs: 10,000-hour roadmap).

## Resulting behavior

Owner directive: the project must stay usable under **MIT**; start executing
the roadmap under that constraint.

1. **License policy made enforceable**: new `scripts/quality/license_audit.py`
   — resolves the union runtime classpath, extracts licenses from POMs in the
   coursier cache (with parent-POM inheritance), classifies semantically
   (verbose GNU/Mozilla/Eclipse names included), fails on forbidden
   (GPL/LGPL/AGPL), review (MPL/EPL/CDDL, unless explicitly allowlisted with a
   dated comment) and UNKNOWN (no license found = failure, not a pass).
   `--self-test` fixtures cover the parser and every classification bucket.
2. **New gate `license-audit`** in the registry, added to the `fast` profile
   (now 10 gates) with self-test. This is an ADDITION (stricter), no check was
   weakened or removed.
3. **Baseline audit**: 25 runtime artifacts, 0 problems — Apache-2.0 (Scala
   toolchain, ZIO, zio-json/streams, izumi-reflect, magnolia,
   scala-collection-compat), BSD-3 (JLine ×4, scala-asm), MIT (slf4j-api).
   The current tree is fully MIT-compatible.
4. **ADR-016 accepted** (owner directive): terminal renderer = custom
   diff-based ANSI over JLine 4 (BSD-3, existing dependency). Lanterna
   rejected (LGPL-3.0); Mordant deferred (Kotlin stdlib); web deferred to
   Phase 7. Fallback if the spike disproves full-screen feasibility: reduced
   inline UI, still MIT-clean.
5. **Roadmap amended** (`docs/roadmap-10k.md`): renderer decision §3.2, Phase-0
   ADR-1 resolved + spike redefined, risk row replaced (LGPL row → custom-
   renderer cost row), new §8 "Licensing policy (MIT-only, enforced)",
   ADR backlog item closed, `THIRD-PARTY-NOTICES` added to the Phase-9
   release checklist.
6. **`docs/licensing.md`** — the policy in one place: buckets, enforcement,
   how to check before adding a dependency, baseline results, known
   exclusions (Lanterna).

## Changed paths

- `scripts/quality/license_audit.py` (new)
- `scripts/quality-policy/registry.json` (new gate; fast profile 9→10 gates)
- `docs/adr/ADR-016-tui-renderer.md` (new, Accepted)
- `docs/licensing.md` (new)
- `docs/roadmap-10k.md` (MIT amendments, §8 added)
- `artifacts/license-audit/report-*.json` (evidence, gitignored artifacts dir)

## Acceptance evidence

| Criterion / test ID | Actual result | Evidence |
| --- | --- | --- |
| Auditor self-test (parser + classification buckets) | PASS | gate log artifacts/quality/20261005-160856/license-audit.log |
| Baseline audit of the runtime graph | 25 artifacts, 0 problems | artifacts/license-audit/report-20261005-*.json |
| Gates with the new audit (fast profile) | 10/10 passed | artifacts/quality/20261005-160856/manifest.json |
| Tamper-evidence self-check after registry edit | ok | quality.py self-check |
| Product code untouched | no scala changes this increment | git diff scope |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `python3 scripts/quality/license_audit.py --self-test` | 0 | stdout |
| `python3 scripts/quality/quality.py self-check` | 0 | stdout |
| `COURSIER_CACHE=/tmp/cc-master make quality-changed QUALITY_EXECUTE=1` | 0 | artifacts/quality/20261005-160856/manifest.json |

## Limitations / blockers

- The auditor reads POMs from the local coursier cache; a dependency whose
  pom is missing from the cache fails as UNKNOWN by design (fail-closed).
  There is no network fetch — audits are offline and reproducible.
- REVIEW_OK is currently empty; if a legitimately-needed MPL/EPL dependency
  appears, the owner adds it with a dated comment (auditable in-repo).
- ADR-016 spike (flicker/resize proof on the terminal matrix) is the next
  card; the renderer decision is accepted but its feasibility bar is not yet
  demonstrated.

## Review and next task

Self-executed under owner direction. Next card: Phase-0 renderer spike
(`ScreenRenderer` cell-buffer + PTY-based frame assertions), then ADR-2 (event
bus schema).
