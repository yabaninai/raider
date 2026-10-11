# RAI-099: work record — M1 acceptance (milestone evidence)

Status: done (evidence compiled; owner sign-off requested). Date: 2026-10-09 UTC.
Baseline: интегрированное W5-дерево (последняя волна, full suite 545/0). Режим:
`Git unavailable: isolated snapshot` (lane-37). У lane нет `artifacts/` — все
числа ниже скомпилированы из work records; каждая строка evidence-таблицы
помечает исходный record. Свежие fingerprints исходников evidence сняты в этой
lane (M1A-01):

| Источник | sha256 |
| --- | --- |
| `docs/work/records/W1-EVENTS-TABS.md` | `f0c7580da15def5a88e919e77e1053280196b2d68366cfc67fa50d48ba07e29f` |
| `docs/work/records/W2-EMITTERS-CANCEL-SHELL.md` | `04fb0f224a3948e0ded8eb104b7264cdca8a73fd123e9013939ff2c0137e8bc8` |
| `docs/work/records/W3-PARALLEL-10.md` | `ced510dd9605588c26ef6a4ecffcb65d430f66ebb65eaefae41f8fca55431573` |
| `docs/work/records/W4-PARALLEL.md` | `16ce8b185a56bf385a91121d9982384ee723b4e79e63694a5d8d0f238158c0e5` |
| `docs/work/records/W5-PARALLEL.md` | `3ecf367607c1869c71347b71ec50d758951c12b5ebcf3e2f52191de546dac053` |
| `docs/work/board.md` (до правки RAI-099) | `c79d8e01304a2f13c002be18c56e78dee2863fc1761fd245c71be20236ada23a` |
| `docs/roadmap-12k.md` | `b8755b8f2cdc7b43e11d037c7f3255129a7dd367f35ad1c12c76ad0eb20ea72a` |
| `scripts/quality-policy/registry.json` | `d3085718e6e322afbd030ce280f16936d06d6c6066b5cd2314c3cb7cf0cf98ba` |

## 1. Что входит в M1

Критерий выхода P2 / определения M1 (roadmap-12k §P2, «Exit M1»): пользователь
запускает задачу, видит 5 независимо работающих агентов, переключается и
возвращается к неизменённому черновику; закрытие вкладки ≠ отмена работы
(running tab предлагает hide или cancel); закрытие root обрабатывает детей;
UI focus не меняет owner/budget/policy; plain chat/REPL/run остаются доступными.

По компонентам:

- **Вкладки**: SessionRegistry на реальном EventHub (P2.A), TabShell на JLine с
  `:tabs/:new/:switch/:next/:prev/:rename/:close` (P2.B), PTY-интеграция
  (P2.C) — RAI-057/061/062 (records W1, W2, W3).
- **Статусы**: job-слой — источник событий; StatusLedger → JobStatusChanged +
  ровно один Terminal на job; StatusLine в chat; EventExport для headless —
  RAI-058/059 (W1, W2) и RAI-060 (W3-PLAN).
- **Cancel**: `cancelOutOfBand`/`cancelAllOutOfBand` вне model admission,
  child deadlines с bounded grace, observer-interrupt ≠ owner-cancel (P3.B,
  RAI-063, W2); Delegated-race fix + root-пул инвариант (RAI-073, W3).
- **Input integrity**: draft байт-точен (raw line сохранён), фокус = только
  UI-состояние (тест-инвариант), one-writer печать, bounded history/scrollback
  с честными eviction-счётчиками (RAI-057/061, W1/W2).

## 2. Evidence-таблица

Статусы «passed (recorded)» означают: проверка выполнена и зафиксирована в
указанном record на интегрированном дереве соответствующей волны; эта lane
не перепрогоняла sbt/PTY (docs-only snapshot lane, см. обязательства §3).

| # | Claim | Evidence-источник | Статус |
| --- | --- | --- | --- |
| E1 | M1-DEMO PTY: 5 вкладок, 1105 событий, 0 потерь, drafts byte-exact, `all_pass=true` (`artifacts/tabs-pty/m1-demo-summary.json`); PTY-01..03 PASS | `W3-PARALLEL-10.md` (RAI-062); повторно подтверждено в `W5-PARALLEL.md` («артефакты W3, дерево не деградировало», full suite 545/0) | passed (recorded) |
| E2 | StatusLine (`raider.cli.chat.StatusLine`) / EventExport (`raider.cli.run.EventExport`): one-line статус из реального hub + consumer-side helpers по `seq` | RAI-060 принят в сессии W3-PLAN — запись в Session History `board.md` («accepted (integrated, gates green)»); отдельного record-файла нет; документировано в `docs/api.md`; исходники присутствуют в снапшоте: `modules/cli/src/main/scala/raider/cli/chat/StatusLine.scala`, `modules/cli/src/main/scala/raider/cli/run/EventExport.scala` | accepted (board entry; standalone record отсутствует) |
| E3 | cancelOutOfBand/deadlines: parked child при maxLLM=1 отменяется ≤5s ×20 без второго слота (CANCEL-01..03); Uncertain честный; observer≠owner; 119/119 в lane; cancelInvoke/tool-схемы не изменены | `W2-EMITTERS-CANCEL-SHELL.md` (RAI-063) — примечание: фактический источник W2, не W3; W3 добавил Delegated-race fix + root-пул инвариант (RAI-073, DR-01..03) | passed (recorded) |
| E4 | Mailbox + steering: bounded Mailbox (msgId-дедуп, ack в одном STM-коммите, turn-boundary, typed Full/TerminalJob) — W3 (RAI-065); wiring per-child cap 32, `sendToChild`, owner-steering на границе turn, final-drain ack ≠ потеря (SENDW-01..03) — W4 (RAI-074) | `W3-PARALLEL-10.md` + `W4-PARALLEL.md` | passed (recorded); модель-инструмент `send_agent` осознанно отсутствует (W5 limitation) |
| E5 | UX03/04 baseline: event→visible p50 53.45 / p95 55.45 ms; REPL warm/cold measured (`artifacts/ux-baseline-root.json`) | `W5-PARALLEL.md` (RAI-089, замер coordinator на root) | measured (baseline, НЕ порог; протокол `docs/reviews/ux-baseline-protocol.md`) |
| E6 | Test-стабилизация: raiderRuntime ×10 подряд → 0 flake (1710 тесто-прогонов); negative control: 1–10/100 сбоев на старом call-index коде; параллельный sbt ×3 стабильно | `W5-PARALLEL.md` (RAI-092) | passed |
| E7 | Интегрированные прогоны волн: full sbt 406/0 (W3) → 476/0 (W4) → 545/0 (W5); EXECUTE + 8/9/9 профилей passed; fast (10 gates) PASSED; validator 76→85→95 cards | `W3-PARALLEL-10.md`, `W4-PARALLEL.md`, `W5-PARALLEL.md` (артефакты quality: `…20261009-172428/172520`, `…20261009-191006`, `…20261009-203453/203554`) | passed (recorded, integrated trees) |

## 3. Honest obligations (НЕ закрыто M1)

1. **Human usability sessions (UX01)** — не проводились; субъектная оценка
   удобства — владелец. UX03/04 (E5) — machine-clock baseline одной машины,
   без порогов; UX-цели остаются future acceptance.
2. **live_verified (провайдеры)** — нет. Live-пробы W1 (T1..T6/R1..R3) —
   только refusal-before-spend; RAI-079 (W4) — HTTP-стенд tool-round, не live.
   Board obligation «Live-wire verification (real providers)» открыта:
   нужен owner-configured endpoint/budget.
3. **Milestone-прогон формальных профилей на интегрированном кандидате** —
   не выполнен и данной карточкой не заявляется. `registry.json` содержит
   только component-stage профили; `stage_policy.milestone` = «obligations
   tracked by board; not runnable until stage target». Формальная M1-приёмка
   (milestone-набор гейтов на едином кандидате) — известное ограничение и
   следующий шаг coordinator/owner; lane-evidence не заменяет проверку
   интегрированного candidate.
4. **Delegated-race edge**: deadline-watchdog в микросекундном окне до
   публикации — теоретическая гонка (LOW, ревью W3; гарантия «publish-attempt
   precedes settle» задокументирована). Остаются мелкие незакрытые заметки W2:
   setChildDeadline latest-wins без отдельного теста; watchdog-мапы не чистятся
   (growth в очень долгоживущем toolset).
5. **Lane-ограничение этой записи**: snapshot без Git и без `artifacts/`;
   evidence скомпилирован из records (fingerprints выше). Свежие прогоны в lane:
   только planning-package validator (см. Commands). Изменение исходников после
   проверок волн потребовало бы повторения затронутых гейтов — записи E1–E7
   привязаны к fingerprint деревьев соответствующих волн, не к этой lane.

## 4. Verdict

**M1 functionally complete — owner sign-off requested.** Вкладки, статусы,
cancel (OOB + deadlines), input integrity и M1-DEMO (5 вкладок, 0 потерь)
закрыты на интегрированном дереве W5 с зелёными component-прогонами.
Подпись владельца требуется по обязательствам §3 (usability, live_verified,
milestone-профили на кандидате). Заявлений M2/M3 нет: plugin loader/hooks,
StateTools, JobJournal и их wiring — за пределами этого вердикта.

## Changed paths

- `docs/work/records/M1-ACCEPTANCE.md` (новый, этот документ).
- `docs/work/board.md` — одна строка M1 в Module Status.

Публичные API/schemas/контракты не менялись.

## Commands (эта lane)

| argv / command | Exit | Log / manifest |
| --- | --- | --- |
| `python3 scripts/quality/quality.py inventory --out <evidence>/before-manifest.json` | 0 | 396 files, до правок; lane-37-evidence |
| `python3 scripts/plan/validate.py --out artifacts/plan-report-m1a.json` | 1 | failed; 30 ошибок — ВСЕ pre-existing (RAI-093..101 markdown/JSON contract mismatches + RAI-093 title/milestone в `docs/tasks/`, вне allowed paths этой карточки); A/B-прогон без правок lane даёт байт-идентичный набор ошибок; 0 ошибок от RAI-099/M1-ACCEPTANCE |
| `python3 scripts/quality/quality.py inventory --out <evidence>/after-manifest.json` + diff с before | 0 | sanity manifest: только 2 allowed файла |

## Limitations / blockers

- **Validator (docs-profile gate plan-validate) красный в снапшоте lane**: 30
  pre-existing ошибок RAI-093..101 (`docs/tasks/*.md` ↔ `backlog.json`
  contract mismatches; снапшот содержит 104 cards против 95 на W5 — карточки
  093..101 зарегистрированы без синхронизации markdown). Файлы `docs/tasks/*`
  вне allowed paths RAI-099 — фикс отдельной карточкой на интегрированном
  дереве; criterion «validate → 0» в lane честно НЕ достигнут (см. Commands).
- Все обязательства §3; отсутствие milestone-stage профилей в registry.
- E2 не имеет standalone work record — опирается на запись board и исходники.
- Артефакты `artifacts/tabs-pty/m1-demo-summary.json` и
  `artifacts/ux-baseline-root.json` существуют в root-дереве, не в lane;
  lane не может их перепроверить побайтово — доверие основано на fingerprints
  records выше.

## Review and next task

Независимое ревью этого документа не проводилось (авторская компиляция);
вердикт ждёт owner sign-off. Блокер перед приёмкой: синхронизация
card-контрактов RAI-093..101 (validator → 0 на интегрированном дереве).
Next по DAG: milestone-прогон формальных
профилей на интегрированном кандидате (coordinator), затем P3.A remainder
(mailbox wiring в model-инструмент), P4.C SDK/docs/fixtures (M2 close),
UX01 human sessions (владелец).
