# P0-WAVE0: work record

Status: done (в границах выполненных карточек; roadmap P0/P1 продолжается).
Date: 2026-10-06 UTC. Baseline commit: `7ee6c0c` + незакоммиченные planning
документы (см. [ROADMAP-12K](ROADMAP-12K.md)); финальное дерево — интегрированный
candidate этого record. Исполнение: coordinator + максимум 2 параллельных writer
в изолированных snapshot-lanes (без `.git`), reviewer — независимые read-only
агенты, самоприёмки не было.

## Resulting behavior

1. **P0.A** — фактическая матрица source/board/evidence W01–W18 и UX01–08:
   [docs/reviews/2026-10-05-p0a-gap-matrix.md](../../reviews/2026-10-05-p0a-gap-matrix.md).
   Подтверждены два дефекта quality runner и найден секрет-в-argv дефект
   (`run/CliArgs.scala:67 --api-key`); send/watch/cancel обязательства зафиксированы
   как P3 (не регресс). Pre-existing validator blocker (битые ADR-ссылки в
   untracked research) закрыт точечной правкой ссылок с сохранением содержимого.
2. **P0.B** — [ADR-024](../../adr/ADR-024-runtime-ui-event-seam.md): frozen
   runtime→UI event seam (envelopes, два класса доставки, subscribe без окна
   потери, progress/honest-unknown, focus-правила). Статус после независимого
   ревью и правок — frozen для P1/P2; 2 раунда ревью, вердикт ACCEPTED после
   устранения единственного residual (CJK-опечатка).
3. **RAI-054** (M0) — quality runner fix: `classify` file-keyed; git-mode
   fingerprint (staged/unstaged/untracked/deleted/renamed; rename = delete+add);
   loud error «Git unavailable: isolated snapshot» вместо молчаливого fast;
   manifest-diff включает symlink target; run_id уникален (timestamp-pid-seq);
   self-check покрывает classify/git/manifest/decision/run-id. Первый честный
   зелёный `make quality-changed QUALITY_EXECUTE=1` на дереве с изменениями.
4. **RAI-055** (M1, P1.A slice) — пакет `raider.runtime.events`: 13 payload-кейсов
   по ADR-024 §5, JSON codec (roundtrip всех кейсов + unknown-discriminator
   отказ), EventError sealed family, чистый SessionReducer (dup-seq идемпотентен,
   regression/post-Terminal → Malformed, LineTail 8×2000), EventHub (reliable
   256 / diagnostics 1024, per-job seq без пропусков, subscribe без окна потери,
   Backpressure typed, diagnostics drop+counter). Найден и исправлен реальный
   дефект: zio 2.1.26 `Queue.bounded.offer` suspends — drop-путь был мёртвым
   кодом; заменён STM try-acquire. Плюс fix F1 (fold-commit до offer — закрытие
   гонки окна потери). 99 тестов модуля зелёные, EventHubSpec стабилен ×3.
5. **RAI-056** (M0, P0.C) — inline renderer PTY spike + probe: JLine printAbove
   сохраняет draft при 200 фоновых записях (Cyrillic/emoji/CJK), TERM=dumb
   plain fallback, 3×67 перекрывающихся burst — 201/201 атомарных строк, 0
   потерь; baseline p50≈0.11–0.15 ms / p95≈0.22–0.29 ms (machine-clock baseline,
   НЕ пороги UX). Width-таблица WCWidth: a=1, я=1, 漢=2, 😀=2, combining=1,
   ZWSP=0. Probe пишет evidence внутрь репо (`artifacts/renderer-spike/`).

Публичный API/схемы: новый пакет `raider.runtime.events` (ADR-024); других
изменений публичных контрактов нет; TraceEvent/JobManager/AgentLoop не менялись.

## Changed paths

- `docs/reviews/2026-10-05-p0a-gap-matrix.md` (новый), `docs/adr/ADR-024-runtime-ui-event-seam.md` (новый)
- `docs/research/yabanin-state-discovery.md` (точечная правка 2 битых ссылок)
- `docs/tasks/backlog.json`, `docs/tasks/m0.md`, `docs/tasks/m1.md`, `docs/tasks/README.md` (карточки RAI-054..057, статусы)
- `scripts/quality/quality.py`, `scripts/quality/README-quality-runner.md` (RAI-054)
- `modules/runtime/src/main/scala/raider/runtime/events/{events,errors,codec,reducer,hub}.scala` (RAI-055, новые)
- `modules/runtime/src/test/scala/raider/runtime/events/{EventCodecSpec,SessionReducerSpec,EventHubSpec}.scala` (RAI-055, новые)
- `modules/repl/src/test/scala/raider/repl/spike/InlineRendererSpike.scala` (RAI-056, новый)
- `scripts/quality/renderer_pty_probe.py` (RAI-056, новый)
- `docs/work/board.md`, этот record

Ключевые sha256 интегрированных артефактов: quality.py `60b6dc74…`, README-runner
`857faaa1…`, hub.scala `b057f5a3…`, events.scala `8f0f427f…`, codec.scala
`f36a75c2…`, errors.scala `01ddcf53…`, reducer.scala `b5c61797…`, spike
`6cb137c8…`, probe `2d5e92fe…`.

## Acceptance evidence

| ID | Actual result | Evidence |
| --- | --- | --- |
| RAI-054 QUALITYRUN-01 | docs-only diff → execute exit 0, status=passed (scratch git-repo fixture) | lane-a-evidence/round-2/qr01-manifest.json |
| RAI-054 QUALITYRUN-02 | unmapped → явный отказ exit 1 до gate | lane-a-evidence/round-2/qr02-execute.log |
| RAI-054 QUALITYRUN-03 | run_id уникален последовательно; cross-process — структурно (pid) | self-check line; README limitation |
| RAI-055 EVT-01..04 | 99 passed / 0 failed модуль raiderRuntime; EventHubSpec ×3 стабильно | lane-a2-evidence/RAI-055.b-iii-gates.log |
| RAI-056 RENDER-01..04 | 4/4 PASS на интегрированном дереве; baseline записан | artifacts/renderer-spike/renderer-pty-summary.json |
| Integrated gates | `quality-changed QUALITY_EXECUTE=1` passed 7/7; `quality-profile static unit contracts runtime` passed 5/5 | artifacts/quality/20261006-120717-54115-0001, artifacts/quality/20261007-005445-83640-0001 (+ финальный прогон после docs-правок — см. Commands) |
| Plan validator | passed; 60 cards; markdown/index/backlog согласованы | artifacts/plan-review/report-p0a2.json и финальный прогон |

## Commands

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `python3 scripts/plan/validate.py --out artifacts/plan-review/report-p0a2.json` | 0 | passed, 60 cards |
| `make quality-changed QUALITY_EXECUTE=1` (после RAI-055+056 интеграции) | 0 | artifacts/quality/20261006-120717-54115-0001/manifest.json |
| `make quality-profile PROFILES="static unit contracts runtime"` | 0 | artifacts/quality/20261007-005445-83640-0001/manifest.json |
| `make quality-changed QUALITY_EXECUTE=1` (ФИНАЛЬНЫЙ, после board/record/статусов) | 0 | artifacts/quality/20261007-010036-85447-0001/manifest.json — passed 7/7 |
| `python3 scripts/plan/validate.py --out artifacts/plan-review/report-final.json` | 0 | passed, 60 cards |
| `python3 scripts/quality/renderer_pty_probe.py` (root, без env) | 0 | artifacts/renderer-spike/renderer-pty-summary.json |
| `python3 scripts/quality/quality.py self-check` | 0 | stdout (run-id/git/manifest/decision tests) |
| Lane-прогоны writer'ов (sbt test/scalafmtCheckAll, PTY probe) | 0 | lane-*-evidence/ (пути в тексте выше) |

## Limitations / blockers

- **Финальные docs-правки (board/record/статусы) сделаны после последнего
  execute-прогона** — затронутые docs-гейты перезапущены финальным
  `make quality-changed QUALITY_EXECUTE=1` (см. Commands); manifests выше
  относятся к своим fingerprint'ам.
- **RAI-055 не подключён к эмиттерам**: AgentLoop/JobManager emission —
  P1.B/P2.C (отдельные карточки с cancellation/ownership/budget тестами).
  Info-замечания ревью (interruption между put и offer; forwarder supervision)
  записаны как будущие обязательства, не дефекты этого slice.
- **RAI-054**: cross-process run_id гарантия структурная (pid), в CI при
  переиспользовании pid в ту же секунду теоретически возможна коллизия —
  задокументировано; hash самого baseline manifest в run-manifest не пишется
  (отдельная будущая карточка).
- **RAI-056**: baseline-числы — machine-clock baseline, пороги UX01–08 НЕ
  доказаны; line-atomicity проверяется на уровне финального cleaned-потока.
- **Transport-сбои subagent-инфраструктуры**: 6 «пустых» возвратов за сессию
  (SSE timeouts); обошлено повторами и декомпозицией пакетов; потерянной работы
  нет (lanes верифицированы manifests до/после).
- `--api-key` в argv (W15/UX01) — найдено, НЕ исправлено (отдельная карточка).
- Human usability (UX01–08 с участниками), soak, live verification — pending
  по roadmap; ничего из этого не заявлено.

## Review and next task

Независимые ревью (все — fresh read-only агенты): RAI-054 ACCEPTED (round-2);
RAI-056 ACCEPTED (round-2); ADR-024 ACCEPTED после D3-fix; RAI-055 —
CHANGES-REQUIRED (F1 MEDIUM + 3 LOW) → b-iii fixes → **ACCEPTED** (delta
re-review); probe-fix ACCEPTED. Board обновлён.

Build skeleton `raiderUi` добавлен в build.sbt (minimal diff 8+/2-, compile
green, EXECUTE passed 7/7 — artifacts/quality/20261007-010442-87399-0001);
RAI-057 переведён в ready. Next ready (по DAG roadmap-12k §5): **RAI-057**
SessionRegistry (P2.A) — frozen ADR-024 типы (есть), skeleton (есть). Затем P1.B (эмиттеры admission/model/tool/retry/
budget в AgentLoop поверх hub) и P2.B/C (inline shell на JLine + PTY
интеграция). Ранний Yabanin/P11 — по-прежнему запрещён до M4.
