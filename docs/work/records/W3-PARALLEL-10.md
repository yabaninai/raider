# W3-PARALLEL-10: work record

Status: done. Date: 2026-10-09 UTC. Baseline: интегрированное W2-дерево + RAI-060.
Режим: OWNER DECISION 2026-10-09 — параллелизм поднят 3→10 потоков (runbook §1
превзойдён решением владельца; зафиксировано в board). 10 изолированных
lanes (base 275/277 файлов), writers — облачные агенты (local-qwen отключён
владельцем), ревью — независимые. Обе батча dispatch + рестарты после 500/abort
прошли без потери работы.

## Resulting behavior (10 карточек accepted)

1. **RAI-062 (P2.C)** — PTY-интеграция вкладок: `TabShellPtyApp` (test-scope) +
   `tabs_pty_probe.py`; PTY-01/02/03 PASS на root; **M1-DEMO: 5 вкладок, 1105
   событий, 0 потерь, drafts byte-exact** (`artifacts/tabs-pty/m1-demo-summary.json`,
   all_pass=true).
2. **RAI-065 (P3.A)** — `raider.runtime.mailbox.Mailbox`: bounded, msgId-дедуп,
   ack в одном STM-коммите, turn-boundary (Draining/Closed), typed Full/
   TerminalJob; flake в спеке (interruptible окно bankCycle) исправлен
   `ZIO.uninterruptible` (reviewer-фикс).
3. **RAI-066 (P4.A)** — **ADR-025** (Proposed, freeze после ревью) + чистый
   skeleton `raider.core.plugin` (descriptor/manifest/preflight, no-IO
   структурно проверено); core 36/36.
4. **RAI-067** — `PatchTool.applyAll`: multi-file unified diff, all-or-nothing
   с честным partial-отчётом; best-effort restore + ToolOutcomeUncertain;
   tools 60/60.
5. **RAI-068** — настоящие REPL completions через dotty 3.9.0
   (`completions(cursor, expr, state)`, protected → bridge-сабкласс);
   JLine Completer + кэш по state-identity; repl 25, repl-engine 9, smoke 0.
6. **RAI-069 (gate-policy, ревью APPROVE)** — профиль security (component):
   secrets-scan gate (fail-closed, masked findings, self-test, SEC-02
   verified-negative); registry diff additions-only; пороги не тронуты.
7. **RAI-070** — UX baseline harness: environment+fingerprint JSON, UX03/04
   честно skipped в lane (classpath) — код замеров доказан smoke-прогоном;
   протокол в docs/reviews.
8. **RAI-071** — `--provider anthropic` (W05 parity): ProviderBackend factory,
   preflight RA-CFG, cli 65/65; transport-профиль passed; HTTP-stand round
   честно deferred (нет стенда в cli).
9. **RAI-072** — docs refresh: api/architecture/contributing под фактический
   API (проверено по исходникам; P11 не как готовое; validator 0).
10. **RAI-073** — Delegated-race fix: park-gate (child parks на Promise до
    публикации Delegated; addFinalizer release; DR-01..03; discriminator
    lastSeq==3). Root-пул инвариант залочен тестом.

## Changed paths

runtime: `mailbox/**` (новый), `delegate/DelegationToolset.scala` (race fix +
null-free), `delegate/DelegationRaceSpec.scala`; tools: `edit/{PatchTool,
UnifiedDiff}.scala`, `MultiPatchSpec.scala`; repl: `repl-engine/DottyReplEngine.scala`,
`repl/{CompletionSupport.scala,Main.scala}`, спеки; ui: `TabShellPtyApp.scala`;
core: `plugin/plugin.scala` + спека; cli: `run/{CliArgs,ProviderBackend,
HeadlessRunner}.scala`, `chat/ChatLoop.scala`, 3 новых спеки; gate-policy:
`registry.json`, `quality.py`, `security_checks.py`; UX: `ux_baseline.py`,
`docs/reviews/ux-baseline-protocol.md`; docs: `{api,architecture,contributing}.md`;
ADR-025; `build.sbt` (raiderUi JLine + raiderCli provider-anthropic);
docs/tasks/* (RAI-065..073), board, этот record.

## Acceptance evidence

| Проверка | Result |
| --- | --- |
| Полный `sbt test` | **406 passed / 0 failed** (runtime 150, cli 65, tools 60, core 36, repl 25, cli-extra 11, ui 16, repl-engine 9, и др.) |
| forbidden_apis / scalafix / scalafmt | 0 violations (фиксы: mailbox uninterruptible, Option-null в DelegationToolset, System.exit→return в PTY-app/spike, переименование fixture-ключей,LazyList drain) |
| EXECUTE + профили | passed: `…20261009-172428` (5/5) и `…20261009-172520` (**8 профилей** static/unit/contracts/runtime/ci/repl/tools/security) |
| M1-DEMO (root PTY) | PTY-01..03 PASS, all_pass=true, 5 tabs / 1105 events / 0 dropped |
| Validator | 76 cards passed |
| Review | 8 карточек ACCEPTED; RAI-069 registry APPROVE (additions-only, fail-closed); fix-list применён полностью |

## Commands (ключевые)

`sbt --batch test` → 406/0; `make quality-changed QUALITY_EXECUTE=1` → 0;
`make quality-profile PROFILES="static unit contracts runtime ci repl tools security"` → 0;
`python3 scripts/quality/tabs_pty_probe.py` → 0 (M1-DEMO all_pass);
`python3 scripts/quality/security_checks.py --self-test` → 0;
`python3 scripts/plan/validate.py` → passed/76.

## Limitations / blockers

- Delegated-race:Deadline-watchdog в микросекундном окне до публикации —
  теоретическая гонка (LOW, ревью; точная гарантия «publish-attempt precedes
  settle» задокументирована).
- RAI-071: tool-round через HTTP-stand внутри cli — deferred (нет стенда в
  модуле); wire покрыт suite provider-anthropic.
- RAI-070: UX03/UX04 числа появятся на прогоне с рабочим classpath (в root
  classpath теперь собирается; протокол готов).
- RAI-069: licensing — information-only; CVE/OWASP-сканер вне скоупа.
- PatchTool rollback — best-effort (crash mid-commit → Uncertain, не silent).
- Mailbox wiring в DelegationToolset/REPL — отдельная карточка (P3.A remainder).
- docs: fast теперь 10 gates; mailbox-секция добавлена в api.md.

## Review and next task

Ревью W3: ACCEPTED ×8 + registry APPROVE; fix-list закрыт (flake, null,
forbidden-apis, docs count). **M1 milestone: функционально закрыт** (вкладки +
статусы + cancel + demo all_pass) — формальная M1-приёмка (milestone-набор
гейтов на интегрированном candidate + honest-обязательства W08-W11/UX) —
следующий шаг coordinator'а. Дальше по DAG: P2 formal M1-acceptance card,
P3.A remainder (mailbox wiring), P4.B plugin manager, P5 state, P6 recovery,
P7 corpus/live. Yabanin live — после M4 (P11); ключ/бюджет у владельца.
