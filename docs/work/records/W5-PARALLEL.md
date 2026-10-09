# W5-PARALLEL: work record

Status: done. Date: 2026-10-09 UTC. Baseline: интегрированное W4-дерево.
Режим: W5×10 (owner: только облачные саб-агенты; local отключён) — 9 карточек
в 9 изолированных lanes + UX-замеры координатором на root + reviewer.

## Resulting behavior (9 карточек accepted)

1. **RAI-083 (P4.B)** — `plugin` subcommand в Main (routing до CliArgs-парса,
   JSON-вывод, exit-коды result.scala; RAIDER_PLUGINS_DIR). cli 105.
2. **RAI-084 (P4.A finish)** — `HookRunner` + `RaiderHook` trait: bounded
   timeout (кооперативный interrupt, НИКОГДА не stop-API), typed outcomes
   (собственная HookOutcome-семья — frozen PluginError не расширялся),
   read-only input seam (никакого policy/budget), no-sandbox задокументирован;
   PLUG-03 constant-pool скан остаётся зелёным. core 61/61.
3. **RAI-085 (P5.B)** — `StateTools`: state_get/put/delete/keys как Tool
   (JSON args), scope по умолчанию Agent(jobId), Root/Global только явно;
   RA-LBUDGET/RA-CAP structured feedback; без store инструменты отсутствуют.
4. **RAI-086 (RAI-023 wiring)** — `--context <file>` → CiContext (untrusted →
   redacted в run.json); run.json `accounting` из РЕАЛЬНОГО BudgetLedger
   snapshot (unknown ⇔ unpriced; preflight-fail → поле отсутствует, не
   выдумывается); summary cost-строка.
5. **RAI-087 (P6.A)** — `JobJournal`: append-only JSONL с rotate, atomic
   checkpoint, recovery: corrupt/partial tail → truncate + honest Uncertain;
   НИКАКОГО auto-replay мутаций; no-fsync contract задокументирован.
6. **RAI-088** — TabShell `:plugins`/`:state` экраны через чистые провайдеры
   (без IO в ui); printAbove one-writer; burst-тест 100 событий.
7. **RAI-089 (координатор, root)** — UX03/04 baseline ЗАМЕРЕНЫ: event→visible
   **p50 53.45ms / p95 55.45ms**; REPL warm/cold measured;
   `artifacts/ux-baseline-root.json` + протокол.
8. **RAI-090** — REPL live env-key (RAIDER_API_KEY/RAIDER_API_KEY_CMD, маска,
   10s bound; отсутствие ключа → notice + mock fallback); anthropic live —
   честный fallback (repl не зависит от provider-anthropic). repl 48/48.
9. **RAI-091 (gate-policy)** — forbidden-apis location allowlist
   (additions-only: system-exit @ raider.cli.Main; skips как явные NOTE;
   lookalike-path negative в self-test). Ревью обязательное — проведено в
   составе пакетного ревью W5 (verdict ACCEPTED).
10. **RAI-092 (test-only)** — delegation-флейки стабилизированы:
    content-based маршрутизация вместо call-index + awaitSettled вместо
    гонки; negative control доказал причину (1-10/100 на старом коде);
    **raiderRuntime/test ×10 подряд → 0 flake** (1710 тесто-прогонов);
    параллельный sbt ×3 стабильно.

## Acceptance evidence

| Проверка | Result |
| --- | --- |
| Полный `sbt test` (final) | **545 passed / 0 failed** |
| fast (10 gates) | PASSED — scalafix+forbidden-apis exercised, 0 нарушений |
| 9 профилей | passed: static/unit/contracts/runtime/ci/repl/tools/transport/security (`…20261009-203554`) |
| M1-DEMO | PTY all_pass (5 вкладок/1105 событий/0 потерь; артефакты W3, дерево не деградировало: full suite зелёный) |
| UX03/04 | измерены на root: p50 53.45 / p95 55.45 ms (baseline, не порог) |
| STAB | ×10 прогонов + negative control + параллельный sbt ×3 |
| Validator | 95 cards passed |

Инциденты интеграции: scalafmt после моих doc-правок HookRunner (упс —
переформатирован); нейтрализация fixture-литералов сломала 3 ассерта
LiveCredentialsSpec (исправлены на нейтральные значения); JobJournal
`parent != null` → `Option(parent).foreach` (scalafix). Все закрыты до
финального зелёного прогона.

## Commands (ключевые)

`sbt --batch test` → 545/0; `make quality-changed QUALITY_EXECUTE=1` → 0;
`make quality-profile PROFILES="fast"` → 0; 9 профилей → 0;
`python3 scripts/quality/ux_baseline.py --out artifacts/ux-baseline-root.json` → 0;
`python3 scripts/plan/validate.py` → passed/95.

## Limitations / blockers

- HookRunner timeout кооперативный (hook, игнорирующий interrupt, держит
  daemon-thread до выхода JVM) — задокументировано; кардинально решается
  только process-isolation (вне бюджета).
- JobJournal: no fsync; single-writer; wiring в runtime loop — отдельная
  карточка (пока никто не вызывает).
- Mailbox wiring: sendToChild — Scala API; `send_agent` модель-инструмент —
  по-прежнему осознанно отсутствует.
- RAI-084 расширяет skeleton (RaiderHook trait) — ADR-025 freeze review
  должен подтвердить добавление (queued).
- REPL anthropic live: blocked on build.sbt edge (raiderRepl →
  provider-anthropic; заметка координатору).

## Review and next task

Пакетное независимое ревью W5 (verdict ACCEPTED, findings применены:
neutralize-скрипт сломал ассерты — исправлено; HookRunner doc-reword).
Next: P4.C SDK/docs/fixtures (M2 close), P5.B wiring (StateTools →
CodingToolset/TabShell), P6.A wiring (journal → loop), UX01 human sessions
(владелец), M2 acceptance run.
