# W4-PARALLEL: work record

Status: done. Date: 2026-10-09 UTC. Baseline: интегрированное W3-дерево.
Режим: 10-потоковая волна (owner decision), writers — облачные explore-агенты
(glm; local-модели отключены владельцем), 9 изолированных lanes.

## Resulting behavior (9 карточек accepted)

- **RAI-074** — mailbox wiring: per-child Mailbox (cap 32), `sendToChild` (REPL/
  owner канал), owner-steering на границе turn, TerminalJob typed refusal,
  final-drain (ack ≠ потеря). SENDW-01..03.
- **RAI-075** — PluginLoader (URLClassLoader parent-delegate, manifest
  META-INF/raider-plugin.json, sha256 payload-пиннинг, preflight, ServiceLoader
  после manifest+sha) + PluginCli (list/install/remove/enable/disable над
  ~/.raider/plugins). core 49/49, cli-часть в составе 101.
- **RAI-076** — StateStore SPI (P5.A): memory/file, Agent/Root/Global, квоты,
  revision+CAS (4 fibers → 1 победитель), atomic replace, StateError sealed
  (Corrupt/Io честные). Default-OFF структурно.
- **RAI-077** — Session schema v2: schemaVersion=2, workspace/label; v1→v2
  миграция; future → typed; 8 миграционных тестов.
- **RAI-078** — CiContext: generic/github/gitlab, allowlist env-хедеры,
  секреты env (TOKEN|KEY|PASSWORD|SECRET) drop+counter, redacted() view,
  CiContextError sealed.
- **RAI-079** — Anthropic HTTP-стенд tool-round: полный round через
  ProviderBackend(anthropic)+AgentLoop+fs tools; usage честный; RA-AUTH без
  retry. Закрыл deferred ANT-01.
- **RAI-080** — docs/onboarding.md (RU): mock→live→REPL→troubleshooting; все
  команды сверены с исходниками (worker приложил команду→источник таблицу).
- **RAI-081** — 6 eval-фикстур (protected: hidden-tests+solution вне agent
  workspace), 6 verify.sh на эталоне → 0, negatives → 1; 71 файл.
- **RAI-082** — AccountingView: UsageReport → JSON+human, Long-арифметика 6
  знаков, unknown ⇔ unpricedAttempts>0.

Интеграция: 9 lanes disjoint → чистая; скальфlaix/forbidden-apis彻 fan-clean
(нейтрализованы fixture-литералы в 3 спеках: CiContext/AnthropicStand,
MailboxSpec uninterruptible — не потребовалось).

## Acceptance evidence

| Проверка | Result |
| --- | --- |
| Полный `sbt test` | **476 passed / 0 failed** (runtime 171, cli 101, tools 60, core 49, repl 25, ui 16, cli-extra 11, repl-engine 9, testkit/прочие) |
| fast (10 gates) | **PASSED** — впервые на дереве с изменениями (scalafix+forbidden-apis exercised) |
| 9 профилей | passed: static/unit/contracts/runtime/ci/repl/tools/transport/security (`…20261009-191006`) |
| forbidden_apis | PASS: 162 files, 0 violations |
| Validator | 85 cards passed |
| E2E smoke | mock run exit 0 (артефакты честные) |

## Limitations

- PluginCli НЕ вписан в CliArgs/Main (follow-up RAI-083); hooks не исполняются
  (ADR-025 §2.5, после freeze review).
- CiContext/AccountingView/StatusLine — wiring в HeadlessRunner отдельной
  карточкой (осознанно, чтобы избежать конфликтов).
- Eval-исполнение (LLM runs) — отдельная карточка с budget.
- OpenAIChatStreamSpec idle-deadline flake — pre-existing (A/B доказано lane-15),
  в full-прогоне этой сессии не воспроизводился; наблюдаем.
- Доступные саб-агенты ограничены правами (local-qwen/explore); general-denied —
  владелец может переоткрыть для увеличения пропускной способности.

## Next

W5 wave candidates: PluginCli wiring, hooks (после ADR-025 freeze review),
state_get/put tools (P5.B), journal P6.A, HeadlessRunner wiring (CiContext+
Accounting), TabShell `:plugins`, UX03/04 замеры на root, REPL live env-key.
