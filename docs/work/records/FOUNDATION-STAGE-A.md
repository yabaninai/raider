<!-- Work record. Original in Russian; key results in English below. -->

# FOUNDATION-STAGE-A + F0 finalization: work record

Status: done (re-review pending). Date: 2026-10-03 (UTC). Baseline: то же untracked
дерево; review-манифест совпадает до F0-правок (проверено в начале сессии).

## Resulting behavior

1. F0 remediation завершена: закрыты все 13 findings независимого ревью
   (N1–N13) — exit propagation без tee-swap + fault-injection self-tests в обоих
   launcher'ах, изоляция TERM/HOME, truthful exit map (failed→20, artifact→27),
   grandchild-репродюсер process-group gap (обязательство RAI-016), typed
   StreamProtocol failures (exit 21) для malformed-SSE/premature-EOF,
   измеряемый before-headers cancel, source manifests, инвентарь toolchain в
   evidence-каталоге, README/usage актуализированы.
2. Языковая политика зафиксирована владельцем: продукт — только Scala 3 + ZIO;
   Python минимизирован и исключён из поставки; локальный OpenAI-compatible
   стенд разрешён для тестов. Внесено в docs/PLAN.md и AGENTS.md.
3. Foundation stage A: первый продуктовый Scala — modules/core v0 (6 файлов:
   ids, RaiderError family, Task (lazy ZIO wrapper), Program с infix andThen +
   mapOutput, Agent/Workflow, Job/Child, ModelBackend + ModelEvent/Usage/
   Capabilities). Сборка scripts/build/compile.sh на pinned dotc 3.9.0:
   positive fixture компилируется, negative (andThen тип-мисматч) корректно
   отвергается, 0 warnings. scripts/bootstrap.sh — glue для jar-ов.

## Changed paths

- experiments/repl-spike/{run_spike.sh, fetch_toolchain.py, harness/repl_pty_harness.py,
  harness/headless_harness.py, headless/HeadlessSpike.scala, probe/HttpJsonProbe.scala, README.md}
- experiments/openai-chat-spike/{run_spike.sh, OpenAiCompatSpike.scala,
  harness/mock_gateway.py, harness/oai_spike_harness.py, README.md}
- modules/core/src/main/scala/raider/core/{ids,errors,task,program,job,backend}.scala (новые)
- contracts/compile/{positive/ProgramCompose.scala, negative/AndThenMismatch.scala} (новые)
- scripts/{bootstrap.sh, build/compile.sh} (новые)
- docs/PLAN.md, AGENTS.md (языковая политика), docs/prompts/opencode-autonomous-scala.txt (новый),
  docs/work/board.md, этот record

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| F0 false-green закрыт | self-tests: java/python3/both → suite корректно FAIL; exit 0 у самотеста | stdout сессии + artifacts/f0-final-*.log |
| F0 финальные прогоны | rai-001: 10/10 headless + 25/25 REPL; oai: 9/9; exit 0 оба | artifacts/f0-final/{rai-001,oai}/ |
| Продуктовая компиляция | exit 0, 0 warnings, negative rejected | artifacts/build.log |
| План-пакет согласован | validate.py exit 0 (56 cards) | artifacts/plan-review/report.json |

## Commands

| argv | Exit |
| --- | --- |
| sh experiments/repl-spike/run_spike.sh --self-test-fault-injection | 0 |
| sh experiments/openai-chat-spike/run_spike.sh --self-test-fault-injection | 0 |
| RAIDER_SPIKE_EVIDENCE=artifacts/f0-final/rai-001 sh experiments/repl-spike/run_spike.sh | 0 |
| RAIDER_OAI_EVIDENCE=artifacts/f0-final/oai sh experiments/openai-chat-spike/run_spike.sh | 0 |
| sh scripts/bootstrap.sh (реальный прогон после re-review; inventory в artifacts/toolchain/) | 0 | artifacts/f0-final-rereview/bootstrap.log |
| sh scripts/build/compile.sh | 0 | artifacts/f0-final-rereview/build.log |
| оба fault-injection self-tests | 0 | artifacts/f0-final-rereview/selftest-{rai001,oai}.log |
| полные suite-прогоны (после self-test) | 0 | artifacts/f0-final-rereview/suite-{rai001,oai}.log + {rai-001,oai}/ |

## Limitations / blockers

- Повторное ревью (round 2): все 13 findings подтверждены исправленными; его
  узкие замечания (bootstrap реально не запускался, self-tests не архивированы,
  MetadataObserved/незавершённые error families, seal Task, weak negative gate,
  OAI-05 grep) закрыты в этой же сессии с повторными прогонами — см. обновлённую
  таблицу Commands и artifacts/f0-final-rereview/.
- sbt не установлен (GitHub медленный) — RAI-003 stage B: brew install sbt или
  артефакты с Central; до тех пор канонический build — dotc-скрипт.
- JDK 20.0.1 на host; JDK 21 — целевая политика (brew openjdk@21), pending.
- modules/core v0 — скелет контрактов; полный RAI-002 freeze (schemas/codecs/
  fixtures) не выполнен.

## Review and next task

Review: F0 fixes — pending re-review; core v0 — review вместе с RAI-002.
Next: docs/prompts/opencode-autonomous-scala.txt (волны Foundation→F4 до W01–W18).
