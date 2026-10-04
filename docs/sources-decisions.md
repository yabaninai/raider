<!-- Translated from Russian original. Key terms preserved as-is. -->

# Источники и решения

Проверено 2026-10-02. Источники подтверждают возможности зависимостей/конкурентов; спецификация Raider — наше проектное решение, не обещание этих библиотек. Upstream main/dev может измениться; compatibility cards закрепляют commit/tag и hashes перед копированием.

## 1. Первичные источники

| Источник | Подтверждённый факт / применение |
| --- | --- |
| [Scala REPL](https://docs.scala-lang.org/scala3/book/taste-repl.html) | Настоящее интерактивное выполнение Scala |
| [Scala CLI REPL](https://scala-cli.virtuslab.org/docs/commands/repl/) | Classpath/source loading, init script и reference launch |
| [Scala 3.9 release](https://scala-lang.org/news/3.9/) | Baseline candidate; REPL отдельный artifact начиная с 3.8 |
| [ReplDriver source](https://github.com/scala/scala3/blob/main/repl/src/dotty/tools/repl/ReplDriver.scala) | Pinned adapter обязателен; compiler state/output/signals требуют spike |
| [JLine interactive features](https://jline.org/docs/advanced/interactive-features/) | Координация вывода с редактируемой строкой |
| [ZIO effects](https://zio.dev/reference/core/zio/) | Lazy effects, errors и fibers |
| [ZIO fibers](https://zio.dev/reference/fiber/fiber.md/) | Fork/join, scopes и interruption |
| [ZIO STM](https://zio.dev/reference/stm/) | Atomic in-memory state/queues/reservations, без I/O внутри transaction |
| [ZIO TestClock](https://zio.dev/reference/test/services/clock/) | Virtual-time deadline/backoff tests |
| [Scala derivation](https://docs.scala-lang.org/scala3/reference/contextual/derivation.html) | Возможность typeclass derivation, не готовая JSON-schema библиотека |
| [Pi repository](https://github.com/earendil-works/pi) | Runtime/provider/UI separation и MIT |
| [Pi extensions](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/extensions.md) | Tools/events/providers/runtime extensions |
| [Pi subagent example](https://github.com/earendil-works/pi/tree/main/packages/coding-agent/examples/extensions/subagent) | scout/planner/reviewer/worker definitions и process-based subagents в примере |
| [Pi RPC](https://github.com/earendil-works/pi/blob/main/packages/coding-agent/docs/rpc.md) | JSONL stdin/stdout, command acceptance не равно final run completion |
| [Pi license](https://github.com/earendil-works/pi/blob/main/LICENSE) | Условия сохранения notices при копировании |
| [OpenCode agents](https://opencode.ai/docs/agents/) | JSON/Markdown profiles и роли агентов |
| [OpenCode source agent registry](https://github.com/anomalyco/opencode/blob/dev/packages/opencode/src/agent/agent.ts) | Permissions/defaults дополнительно к prompt files |
| [OpenCode plugins](https://opencode.ai/docs/plugins/) | Hooks/tools/plugin ecosystem, TypeScript runtime |
| [OpenCode server](https://opencode.ai/docs/server/) | HTTP sessions/events/prompt/abort API |
| [OpenCode license](https://github.com/anomalyco/opencode/blob/dev/LICENSE) | Notices для переносимых материалов |
| [Anthropic streaming](https://platform.claude.com/docs/en/build-with-claude/streaming) | Indexed content blocks, tool JSON deltas, cumulative usage |
| [Anthropic tools](https://platform.claude.com/docs/en/agents-and-tools/tool-use/define-tools) | Messages tool schemas |
| [GitHub privileged PR event](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target) | Fork code рядом с secrets недопустим |
| [GitHub workflow commands](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-commands) | Summary/output file channels |
| [GitLab report artifacts](https://docs.gitlab.com/ci/yaml/artifacts_reports/) | JUnit adapters |
| [OpenCode commands](https://opencode.ai/docs/commands/) | Project commands и agent metadata |
| [OpenCode TUI](https://opencode.ai/docs/tui/) | Launch и file references |
| [Scalafmt installation](https://scalameta.org/scalafmt/docs/installation.html) | Check-only formatting gates |
| [Scalafix installation](https://scalacenter.github.io/scalafix/docs/users/installation.html) | Check-only rules, нельзя переписывать source в CI до check |
| [sbt-scoverage](https://github.com/scoverage/sbt-scoverage) | Coverage thresholds/reports, не доказательство correctness |
| [OWASP Dependency-Check integrations](https://dependency-check.github.io/DependencyCheck/components.html) | JVM/SBT scanning options для выбора в foundation |
| [Z.ai Coding Plan](https://docs.z.ai/devpack/overview) | Development account/tool capabilities проверять отдельно от продукта |
| [ZCode connection docs](https://zcode.z.ai/en/docs/configuration) | Различие general и coding API endpoints |

Source ReplDriver особенно важен: его `bind` в изученном main фактически не даёт обещанного object binding, а output redirection затрагивает process globals. Не основывать архитектуру на воображаемом stable JSR-223/REPL bind API; shared classloader/prelude bridge и signals подтверждаются prototype.

## 2. Принятые решения

| ADR | Решение | Причина |
| --- | --- | --- |
| ADR-001 | Настоящая Scala REPL, без custom pseudo-Scala parser | Переменные, типы, композиция и перенос в файл |
| ADR-002 | `.ask/.start` primary interactive verbs; apply создаёт Task | Короткие команды при явных effects |
| ADR-003 | Program для Agent/Workflow/Session; один runtime interpreter | Единообразие и расширяемость |
| ADR-004 | ZIO 2, без dual Cats/ZIO abstraction | Scope/cancellation/STM и меньше surface для простых моделей |
| ADR-005 | Native loop в MVP; pi/OpenCode как importers и backends | Независимый runtime и использование существующих экосистем |
| ADR-006 | Yabanin по HTTP, без доступа к его DB/internal Go | Независимые релизы и ясная trust boundary |
| ADR-007 | Mock/replay-first gates; paid eval отдельно | Воспроизводимость, скорость, контроль spend |
| ADR-008 | Single owner/root budget; cancellation/uncertainty explicit | Предсказуемая multiagent работа |
| ADR-009 | Archive != replay; arbitrary Scala durable resume не обещается | Side effects и closure serialization |
| ADR-010 | Definition reload только для новых jobs | Понятные версии и reproducibility |
| ADR-011 | CI-primary runner и отдельный dev/compiler distribution | Portable execution без compiler overhead |
| ADR-012 | OpenAI/Anthropic first-class, Yabanin optional | Standalone runtime и независимый gateway |
| ADR-013 | Trusted checks/policy/artifacts отделены от model output | CI успех подтверждается actual evidence |
| ADR-014 | Existing yb ci wrapper-first для gateway users | Использование готовой поверхности без дублирования owners |

«Принято» означает исходное решение проектного пакета; при необходимости изменение оформляется узкой ADR card с evidence, owner review и обновлением dependent fixtures.

## 3. Feasibility decisions

RAI-001: exact Scala/JDK/sbt/REPL artifact versions; compiler classloader/prelude mechanism; terminal ownership; HTTP transport и schema library compatibility; feasible cold-start/interruptibility. Все versions pin после proof, не copy из search snippets.

RAI-002: concrete effect/backend event signature, codec evidence, `.named/.andThen/all` inference, API namespace, loaded script DefinitionContext. RAI-004: scanner/instrumentation/PTY integration tool versions и exception policy.

## 4. Решения, требующие владельца к соответствующему этапу

- Project license и branding policy до публикации первого distribution.
- Supported OS matrix; default v1 target Linux/macOS, Windows только после проверок.
- Account/cap/authorization для live provider evaluation.
- Exact performance reference machine и measurable baseline.
- Cross-process budget authority/daemon policy только перед P2.

Отсутствие этих решений не останавливает local mock prototype/contracts/build. Оно не разрешает платные вызовы или публикацию автоматически.

## 5. Как обновлять исследование

Перед cross-product card пересчитать hashes snapshot files, проверить git source state и current deployment capabilities. Если source изменился, обновить relevant contract/fixture отдельно от claims о readiness. Не считать старый HEAD доказательством текущего working tree или server behavior.
