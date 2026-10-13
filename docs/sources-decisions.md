# Sources and decisions

Verified 2026-10-02. Sources confirm the capabilities of dependencies/competitors; the Raider specification is our design decision, not a promise made by those libraries. Upstream main/dev may change; compatibility cards pin the commit/tag and hashes before copying.

## 1. Primary sources

| Source | Confirmed fact / use |
| --- | --- |
| [Scala REPL](https://docs.scala-lang.org/scala3/book/taste-repl.html) | Real interactive Scala execution |
| [Scala CLI REPL](https://scala-cli.virtuslab.org/docs/commands/repl/) | Classpath/source loading, init script, and reference launch |
| [Scala 3.9 release](https://scala-lang.org/news/3.9/) | Baseline candidate; REPL has been a separate artifact since 3.8 |
| [ReplDriver source](https://github.com/scala/scala3/blob/main/repl/src/dotty/tools/repl/ReplDriver.scala) | A pinned adapter is required; compiler state/output/signals require a spike |
| [JLine interactive features](https://jline.org/docs/advanced/interactive-features/) | Coordinating output with the line being edited |
| [ZIO effects](https://zio.dev/reference/core/zio/) | Lazy effects, errors, and fibers |
| [ZIO fibers](https://zio.dev/reference/fiber/fiber.md/) | Fork/join, scopes, and interruption |
| [ZIO STM](https://zio.dev/reference/stm/) | Atomic in-memory state/queues/reservations, with no I/O inside a transaction |
| [ZIO TestClock](https://zio.dev/reference/test/services/clock/) | Virtual-time deadline/backoff tests |
| [Scala derivation](https://docs.scala-lang.org/scala3/reference/contextual/derivation.html) | Typeclass derivation capability, not a ready-made JSON-schema library |
| [Pi repository](https://github.com/earendil-works/pi) | Runtime/provider/UI separation and MIT |
| [Pi extensions](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/extensions.md) | Tools/events/providers/runtime extensions |
| [Pi subagent example](https://github.com/earendil-works/pi/tree/main/packages/coding-agent/examples/extensions/subagent) | scout/planner/reviewer/worker definitions and process-based subagents in the example |
| [Pi RPC](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/rpc.md) | JSONL stdin/stdout; command acceptance is not the same as final run completion |
| [Pi license](https://github.com/earendil-works/pi/blob/main/LICENSE) | Requirements for retaining notices when copying |
| [OpenCode agents](https://opencode.ai/docs/agents/) | JSON/Markdown profiles and agent roles |
| [OpenCode source agent registry](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/agent/agent.ts) | Permissions/defaults in addition to prompt files |
| [OpenCode plugins](https://opencode.ai/docs/plugins/) | Hooks/tools/plugin ecosystem, TypeScript runtime |
| [OpenCode server](https://opencode.ai/docs/server/) | HTTP sessions/events/prompt/abort API |
| [OpenCode license](https://github.com/anomalyco/opencode/blob/dev/LICENSE) | Notices for materials being ported |
| [Anthropic streaming](https://platform.claude.com/docs/en/build-with-claude/streaming) | Indexed content blocks, tool JSON deltas, cumulative usage |
| [Anthropic tools](https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools) | Messages tool schemas |
| [GitHub privileged PR event](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target) | Fork code must not run alongside secrets |
| [GitHub workflow commands](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-commands) | Summary/output file channels |
| [GitLab report artifacts](https://docs.gitlab.com/ci/yaml/artifacts_reports/) | JUnit adapters |
| [OpenCode commands](https://opencode.ai/docs/commands/) | Project commands and agent metadata |
| [OpenCode TUI](https://opencode.ai/docs/tui/) | Launch and file references |
| [Scalafmt installation](https://scalameta.org/scalafmt/docs/installation.html) | Check-only formatting gates |
| [Scalafix installation](https://scalacenter.github.io/scalafix/docs/users/installation.html) | Check-only rules; do not rewrite source in CI before checking |
| [sbt-scoverage](https://github.com/scoverage/sbt-scoverage) | Coverage thresholds/reports, not proof of correctness |
| [OWASP Dependency-Check integrations](https://dependency-check.github.io/DependencyCheck/components.html) | JVM/SBT scanning options to evaluate in foundation |
| [Z.ai Coding Plan](https://docs.z.ai/devpack/overview) | Verify development account/tool capabilities separately from the product |
| [ZCode connection docs](https://zcode.z.ai/en/docs/configuration) | Difference between general and coding API endpoints |

The ReplDriver source is especially important: its `bind` in the examined main
does not in fact provide the promised object binding, and output redirection
affects process globals. Do not base the architecture on an imagined stable
JSR-223/REPL bind API; the shared classloader/prelude bridge and signals are
confirmed by the prototype.

## 2. Accepted decisions

| ADR | Decision | Reason |
| --- | --- | --- |
| ADR-001 | Real Scala REPL, no custom pseudo-Scala parser | Variables, types, composition, and moving work into a file |
| ADR-002 | `.ask/.start` are primary interactive verbs; apply creates Task | Short commands with explicit effects |
| ADR-003 | Program for Agent/Workflow/Session; one runtime interpreter | Consistency and extensibility |
| ADR-004 | ZIO 2, no dual Cats/ZIO abstraction | Scope/cancellation/STM and smaller surface for simpler models |
| ADR-005 | Native loop in MVP; pi/OpenCode as importers and backends | Independent runtime and use of existing ecosystems |
| ADR-006 | Yabanin over HTTP, no access to its DB/internal Go | Independent releases and a clear trust boundary |
| ADR-007 | Mock/replay-first gates; paid evaluation separate | Reproducibility, speed, spend control |
| ADR-008 | Single owner/root budget; explicit cancellation/uncertainty | Predictable multiagent work |
| ADR-009 | Archive != replay; no promise of durable resume for arbitrary Scala | Side effects and closure serialization |
| ADR-010 | Reload definitions only for new jobs | Clear versions and reproducibility |
| ADR-011 | CI-primary runner and separate dev/compiler distribution | Portable execution without compiler overhead |
| ADR-012 | OpenAI/Anthropic first-class, Yabanin optional | Standalone runtime and independent gateway |
| ADR-013 | Trusted checks/policy/artifacts separate from model output | CI success is confirmed by actual evidence |
| ADR-014 | Existing yb ci wrapper-first for gateway users | Use the available surface without duplicating owners |

“Accepted” means the original decision in the project package; if it needs to
change, create a narrow ADR card with evidence, owner review, and updates to
dependent fixtures.

## 3. Feasibility decisions

RAI-001: exact Scala/JDK/sbt/REPL artifact versions; compiler classloader/prelude mechanism; terminal ownership; HTTP transport and schema library compatibility; feasible cold-start/interruptibility. Pin all versions after proof; do not copy from search snippets.

RAI-002: concrete effect/backend event signature, codec evidence, `.named/.andThen/all` inference, API namespace, loaded script DefinitionContext. RAI-004: scanner/instrumentation/PTY integration tool versions and exception policy.

## 4. Decisions requiring the owner at the relevant stage

- Project license and branding policy before publishing the first distribution.
- Supported OS matrix; default v1 target is Linux/macOS, Windows only after checks.
- Account/cap/authorization for live provider evaluation.
- Exact performance reference machine and measurable baseline.
- Cross-process budget authority/daemon policy only before P2.

The absence of these decisions does not block the local mock prototype/contracts/build. It does not automatically authorize paid calls or publication.

## 5. Updating research

Before a cross-product card, recalculate hashes for snapshot files and check git source state and current deployment capabilities. If source changes, update the relevant contract/fixture separately from readiness claims. Do not treat an old HEAD as evidence of the current working tree or server behavior.
