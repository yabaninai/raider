# AUDIT-20261101 security lane work record

Status: done (inspection only). Date: 2026-11-01. Baseline commit/source: `04f74780891e0e7032bf98b3d1dccac279f32354`; lane HEAD at inspection: `935cc2ff0e3498ff1080aad4d53e9b6ca157a405`.

## Result

The current source has useful local security controls: API credentials are sourced from environment/helper references rather than argv, file tools canonicalize paths under a chosen workspace, process execution uses structured argv and clears inherited environment, portable launch authority comes from a launcher-pinned registry, and plugin payload hashes are rechecked after copy. These controls do not make Scala REPL/plugin code a sandbox; the contributor rules correctly say trusted Scala runs with process permissions.

The inspected security acceptance is materially incomplete for an installable/CI product. The registered `security` profile runs a changed-line secret-shape scan only. It does not run a dependency vulnerability scanner; the registered license gate invokes only its parser self-test; no SBOM generation or release security profile is registered. The design requirements in `docs/quality-gates.md` §§3–4 and release profile remain obligations, not completed evidence.

## Findings and follow-ups

1. **P1 — security checks are not part of the default fast CI path, and the scanner is narrow.** `scripts/quality-policy/registry.json:41-55` defines `fast` without `security`; `:88-93` registers `security` separately. The path map at `:227-256` maps module changes to static/unit/contracts, scripts to self/static, and build changes to static/contracts, without selecting security. `.github/workflows/raider-fast.yml:25-29` executes the resulting changed-path plan. The scanner at `scripts/quality/security_checks.py:6-27, 322-354` checks changed/additional lines for seven token shapes, not repository history, all tracked files, arbitrary credential formats, or dependency CVEs. Also, `added_lines` warns and skips symlinks, non-regular, unreadable, and >5 MB files (`:146-171`) while the main path can still return exit 0 (`:337-354`). **Follow-up:** make security activation explicit for CI/release and fail closed (or report unavailable/non-pass) for skipped inputs; define the intended scan coverage and add a verified dependency scanner over resolved application and build-plugin dependencies.

2. **P1 — tool access has workspace containment but no protected-path policy.** `ReadTool.resolveSafe` (`modules/tools/src/main/scala/raider/tools/files/read/ReadTool.scala:124-156`) rejects traversal and symlink escapes, then reads any regular in-workspace file (`:73-117`). `Workspace` carries only a root path (`:16-31`). The inspected tool surface shows no protected-verifier exclusion. Thus if a caller roots the workspace at a repository containing protected verifier files, those files are within the read boundary. This does not establish that hidden verifier material is currently located in such a workspace; it shows the W06 guarantee (“secrets/protected verifier files inaccessible to tools,” `docs/working-product.md:28-31`) is not enforceable by the inspected path boundary alone. **Follow-up:** add an explicit immutable policy/capability for protected paths and test reads, search/tree discovery, writes, and process cwd against it before claiming W06.

3. **P1 — license and dependency/release evidence is not active acceptance.** The registry's `license-audit` argv is `license_audit.py --self-test` (`scripts/quality-policy/registry.json:194-200`), which does not audit the resolved graph. The real audit script does export the runtime classpath and inspect Maven POM license labels (`scripts/quality/license_audit.py:167-249`), but that command is not registered. `security_checks.py:30-33, 203-213` explicitly labels its build.sbt coordinate summary informational and CVE scanning a non-goal. No SBOM generation is present in the inspected scripts, packaging, or workflows; the quality-gates release profile and required transitive-artifact SBOM/notices are specified but not registered (`docs/quality-gates.md:66-73, 93-103`). **Follow-up:** register actual runtime/build-plugin license and vulnerability gates with pinned analyzer/database behavior, generate a machine-readable SBOM including transitive compiler/JLine artifacts, and attach it/notices to release evidence. Until then, do not claim dependency or release security verification.

4. **P2 — verifier/bundle and plugin trust is pinned in some paths, but artifact authenticity/isolation remains an acceptance obligation.** The portable registry is launcher-owned and document data can name only pinned profile/toolset/check definitions (`modules/cli/src/main/scala/raider/cli/portable/PortableTrustedRegistry.scala:25-43, 56-70`). That is a good authority boundary for this slice. Separately, `BundleLoader` loads classes via a URLClassLoader with the application loader as parent and explicitly says strict isolation is a future security-profile obligation (`modules/cli/src/main/scala/raider/cli/run/BundleLoader.scala:11-19, 64-97`). Plugin installation verifies payload SHA-256 and co-install conflicts (`modules/cli/src/main/scala/raider/cli/run/PluginCli.scala:25-30, 157-198`), but the code itself distinguishes integrity from trust (`:57-60`); a digest alone is not publisher authentication. **Follow-up:** keep bundles/plugins explicitly trusted local code until the required trusted-bundle policy, provenance, and isolation acceptance is implemented and reviewed. Do not describe hash verification as signature/trust verification.

5. **P2 — secret handling has positive controls but only partial gate coverage.** `Credentials` documents env/helper sourcing, redacted errors, and a bounded helper (`modules/cli/src/main/scala/raider/cli/run/Credentials.scala:10-21, 75-99`). REPL prelude string escaping is intended to prevent secrets from leaking through compile diagnostics (`modules/repl/src/main/scala/raider/repl/Main.scala:400-413`). These are inspected implementation claims; no tests were run in this audit. The profile scanner's credential pattern list is finite (`security_checks.py:60-70`) and the test-file whitelist is broad (`:75-77`). **Follow-up:** keep stdout/stderr/artifact redaction acceptance across live CLI/REPL/helper and failure paths, add coverage for malformed/alternate key formats and scanner skip cases, and avoid treating placeholder/test allowlists as proof of production secret hygiene.

## Evidence classification

- **Inspected:** source and policy references cited above; contributor/task requirements; current `working-product.md`, `quality-gates.md`, parallel runbook, and work-record template.
- **Current checks executed:** none. This was a read-only security review; no build, gate, test, scanner, credential helper, network call, or paid call was run.
- **Historical evidence:** implementation comments and self-test code were read as source, not accepted as execution evidence. The scanner self-test only establishes its embedded fixture behavior when run; no current result was produced.
- **Not established:** current vulnerability inventory, license compliance of the resolved graph, SBOM contents, secret cleanliness of repository history, CI behavior on a real PR, hidden verifier locations, and release/package security.

## Commands

| Command | Exit | Result |
| --- | ---: | --- |
| `git status --short` (before inspection) | 0 | Clean lane worktree. |
| `git branch --show-current` | 0 | `audit/20261101-security`. |
| `git rev-parse HEAD` | 0 | `935cc2ff0e3498ff1080aad4d53e9b6ca157a405`. |
| `git diff --quiet 04f74780891e0e7032bf98b3d1dccac279f32354 HEAD -- modules contracts examples build.sbt project scripts/quality scripts/quality-policy .github packaging` | 0 | No product/security/build/CI/package diff from the specified baseline in the inspected paths. |
| `git status --short` (after inspection, before record) | 0 | Clean before the permitted record write. |
| Read-only `sed`/`nl`/`rg` inspections of the cited files | 0 for relevant reads | Inspected source; one broad search emitted truncation, so conclusions above rely on scoped follow-up reads. No credentials/config values were read or printed. |

## Limitations

This lane is inspection only and makes no claim that any security gate passed. The existing Scala trusted-code risk is an explicit product boundary, not itself reported as a defect. This record is operational audit material and is excluded from the product input fingerprint by the documented exact records allowlist.

## Review and next task

Independent review is pending coordinator integration. Recommended next work: create narrowly scoped cards for protected-path enforcement and security-profile coverage, then activate dependency/license/SBOM/release checks under a separately reviewed gate-policy change. Re-run affected gates on the integrated candidate; this lane supplies no green evidence.
