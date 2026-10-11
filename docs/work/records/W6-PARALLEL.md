# W6-PARALLEL: work record

Status: done (после fix-раунда по независимому ревью). Date: 2026-10-09 UTC.
Baseline: commit `03828a9` (владелец закоммитил W4/W5 дерево). Режим: W6×10 —
9 карточек в 9 lanes + ADR-025 freeze review + UX (координатор).

## Resulting behavior (9 карточек accepted)

1. **RAI-083** — `plugin` subcommand в Main (routing до CliArgs, JSON, exit
   result.scala). cli 124/124 (в составе).
2. **RAI-084** — HookRunner/RaiderHook (bounded 2s default, кооперативный
   interrupt, HookOutcome sealed, frozen PluginError не расширялся). core 61.
3. **RAI-085** — StateTools (state_get/put/delete/keys, opt-in, scope
   Agent/Root/Global, RA-LBUDGET/RA-CAP feedback).
4. **RAI-086** — `--context` → CiContext в run.json (untrusted → redacted);
   `accounting` из реального BudgetLedger (unknown честный; preflight-fail →
   поле отсутствует).
5. **RAI-087** — JobJournal: append-only JSONL+rotate, atomic checkpoint,
   corrupt/partial tail → truncate + Uncertain, no auto-replay.
6. **RAI-088** — TabShell `:plugins`/`:state` (чистые провайдеры, один writer).
7. **RAI-092** — delegation-флейки стабилизированы (content-based routing
   вместо call-index; awaitSettled вместо гонки): raiderRuntime ×10 → 0 flake
   (1710 прогонов), negative control 1-10/100 на старом коде.
8. **RAI-090/098** — REPL live env-key + **live anthropic** (build edge
   raiderRepl→provider-anthropic, SSE, escape-тесты, маска; openai-путь
   байт-неизменен). repl 55/55.
9. **RAI-091** — forbidden-apis location allowlist (additions-only, NOTE-скипы,
   lookalike negative; gate-policy ревью — ACCEPTED в пакетном ревью W6).

Интеграция: два hand-merge (AgentLoop: journal-scaladoc × plugins-param;
HeadlessRunner: context/accounting × ci-wrappers) — ревью подтвердило «clean
union» после fix-раунда. Исправлены 5 интеграционных пропусков из ревью
(LoopEvents journal wiring, CodingToolset state opt-in, ShellViews adapter,
3 недостающих спеки, board M1-строка).

## Acceptance evidence

| Проверка | Result |
| --- | --- |
| Полный `sbt test` | **569 passed / 0 failed** (runtime 205, cli 124, tools 63, core 70, repl 55, ui 23, …) |
| fast (10 gates) | PASSED |
| 9 профилей (incl. security/transport/tools/repl) | passed (`…20261009-223502`) |
| ADR-025 freeze review | CHANGES-REQUIRED → R1-R8 amendments применены → **Status: Frozen** (ревью-верdict: «code needs nothing») |
| build.sbt edges | ratify: tools→runtime (no cycle), repl→provider-anthropic; raiderCore test-sources += contracts/plugins |
| registry | +`^examples/` → docs (additions-only, RAI-096 follow-up) |
| Validator | 104 cards passed |

## Limitations

- HookRunner timeout кооперативный (process isolation вне бюджета).
- JobJournal: no fsync, single-writer; loop-wiring: Terminal-публикация на
  job-уровне (RAI-059) — journal видит emission-канал loop (срез честно
  задокументирован).
- Hook instance wiring — open contract (ADR-025 §7 freeze note, owner card).
- tools→runtime edge «dead» до RAI-094 (теперь использован).
- Snapshot zinc-сталeness (известный lane-артефакт) — лечится clean.

## Review

Пакетное независимое ревью W6: 12 ACCEPTED + 5 integration drops (все
исправлены дословно из lanes) → финальные suites зелёные; AgentLoop/
HeadlessRunner merges подтверждены как clean union.
