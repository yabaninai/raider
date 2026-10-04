# Независимая оценка Raider, 2026-10-03

Verdict: **changes-required** для полной приёмки RAI-001 и SPIKE-OAI-01; product readiness **not implemented**. Основание — чтение текущих файлов, три независимых направления review и повторение доступных локальных проверок. Это аудит разработки; исходники обоих spikes при оценке не менялись.

## Что сделано

Подготовлен подробный дизайн, 53 Raider cards/3 companion cards, contracts и workflow для исполнителей. Есть feasibility prototypes: три Scala source RAI-001 с настоящим compiler/REPL, ZIO jobs, headless process/signal demo, HTTP/JSON probes; дополнительно Scala OpenAI-compatible клиент с mock gateway и шестью заявленными сценариями. Нет `build.sbt`, `Makefile`, `modules/`, продуктового native loop, полноценных provider adapters, Tool runtime, Program/Task DSL или устанавливаемого Raider CLI. Пример `RaiderSpike.start(...)` не является production `worker.start(...)`.

Git на момент обзора без commits, исходники untracked. Поэтому новый prompt обязан сохранить untracked baseline; worktree от несуществующего HEAD не подходит. OpenCode в среде: `1.18.34`, подтверждены `--version` и `run --help` без inference calls.

## Что воспроизведено

| Проверка | Наблюдение | Практический вывод |
| --- | --- | --- |
| Fresh Scala compile | exit 0, три source в отдельный classes directory | Compiler/ZIO/codec candidate реально работает на проверенном host |
| PTY с xterm и изолированной JVM home | 25/25, exit 0 | Основная REPL feasibility подтверждена |
| PTY с унаследованным TERM=dumb | 22/25, exit 1 | Harness environment сейчас недостаточно воспроизводим |
| Дополнительная foreground отмена | После Ctrl+C и prompt за 2s нет новых stream chunks | Прерывается sample streaming fiber, не только observer |
| Headless normal/SIGTERM/SIGINT | 0/143/130; JSON state ожидаемый; прямой child отсутствует по OS probe | Подтверждён ограниченный signal/direct-child сценарий |
| JSON probe | 3/3, exit 0 | Базовая codec derivation проверена |
| Fault injection launcher | fake java/python3 возвращают 17, весь suite возвращает 0 | Основной runner способен сообщить ложный успех |
| Повтор HTTP | loopback bind запрещён sandbox | Не воспроизведён; исходное evidence не заменяет новый pass |

Оригинальные `artifacts/rai-001` сохранены. Независимые logs/transcripts/reproducer скопированы в `artifacts/readiness-review-2026-10-03/`. Source/evidence hashes и observed results сохранены в [review manifest](2026-10-03-evidence.json). Полный исходный headless harness упирался в запрещённый `ps`; отдельная проверка проверяла только собственный child PID, без обхода sandbox.

## Приоритетные findings

| Severity | Место на reviewed baseline | Сценарий / дефект | Требуемое исправление |
| --- | --- | --- | --- |
| P0 для gate trust | `run_spike.sh:10,29,44,47,70,73` | `set -e` плюс producer `| tee` теряет exit producer; воспроизведено all-fail→0 | Propagate actual exit; fault injection каждого шага; fresh output без stale classes |
| P1 | `repl_pty_harness.py:61–64` | setdefault сохраняет TERM=dumb; JVM history направляется в home пользователя | Test-owned TERM/capabilities/home/history, separate dumb/noTTY capability tests |
| P1 | `HttpJsonProbe.scala:35–59,85–87` | sendAsync без cancellation hook; blocking read на compute pool; timeout result discarded и затем unbounded await | Before-headers/stalled-body cancel fixtures, interruptible I/O, bounded acquire/finalization, incremental UTF-8 |
| P1 | `HeadlessSpike.scala:15–18,43–54` | Claimed group cleanup реализован только для одного sleep PID | Narrow claim либо реальная process-tree/group implementation с grandchildren/resistant-child tests |
| P1 | `HeadlessSpike.scala:101–113,117–133` | Failure может дойти до exit0; artifact-written flag до успешной записи | Production error/exit state machine, reporter fault injection; sample не копировать как готовый runner |
| P2 | `repl_pty_harness.py:315–324`, original record | Summary сохранена до последнего check; original source hashes отсутствуют | Полные check IDs и immutable run/source/toolchain manifests |
| P2 | `fetch_toolchain.py:112–114,134–137,175` | Resolver пропускает зависимости/ошибки и возвращает0; hashes после download только inventory | Стандартный pinned resolver/build; dependency lock/verification policy |
| P1 execution plan | original RAI-018→repl gate, RAI-016→ci gate | Gate требует компоненты более поздних зависимых карточек | Component/stage registry, все end-to-end obligations остаются mandatory на milestone |
| P1 execution plan | original dependency/build→full, CI только RAI-045 | Full ещё невозможно выполнить, minimal development CI слишком поздно | Foundation gates и ранний CI slice; полный final acceptance на общей сборке |
| P1 delivery | old OpenCode prompt/model workflow | Single writer и нет протокола передачи/integration lanes | Explicit isolated parallel lanes, shared ownership, coordinator/reviewer и patch preconditions |

Paths первых семи строк относительны `experiments/repl-spike/` и его `harness/probe/headless` subdirectories. Source-level HTTP/headless risks не объявлены воспроизведёнными network/failure bugs; соответствующие negative fixtures ещё нужны. Прямой child cleanup не доказывает group cleanup. Xterm feasibility не доказывает finished terminal renderer/JDK21/Linux compatibility.

## Уточнение статуса RAI-001

Полезная feasibility доказана частично, но original scope целиком не принят. Review требует исправить ложный green/evidence/env и зафиксировать remaining obligations. Background output interleaving уже признан первоначальным record, хотя original acceptance требует неповреждённый edit buffer. Допустима только явная reviewed scope split: spike доказывает feasibility; RAI-019 владеет production renderer acceptance. JDK21/Linux и stalled HTTP отдельно остаются pending, а не pass.

ADR-015 — candidate decisions с указанным proof scope. Утверждения «HTTP cancellation ready», «ThreadDeath косметика» и «process group cleanup proven» нельзя переносить на production без дополнительных контрактных проверок.

## Дополнение: SPIKE-OAI-01

Новый [OpenAI-compatible spike](../../experiments/openai-chat-spike/README.md) обнаружен при повторной сверке дерева. Сохранённый авторский `artifacts/oai-spike/summary.json` сообщает **6/6**: nonstream, fragmented stream, сборка tool arguments, auth error, cancel/disconnect и REPL. Это полезное отдельное transport feasibility evidence; RAI-011/012 им не закрыты. Независимый HTTP replay не выполнялся: в этой sandbox ранее запрещён loopback bind. Авторские 6/6 не обозначены как повторённые reviewer.

Независимо выполнены fresh compile одного Scala source в новый каталог (**exit 0**) и fault injection launcher: fake `java/python3` с **exit 17 → suite exit 0**, включая `OAI SPIKE SUITE COMPLETE`. Logs, exact compile argv, hashes пяти исходных файлов и captured author checks сохранены в `artifacts/readiness-review-2026-10-03/openai-addendum/`, индекс — в [review manifest](2026-10-03-evidence.json).

| Severity | Место в `experiments/openai-chat-spike/` | Finding / необходимая приёмка |
| --- | --- | --- |
| P0 для gate trust | `run_spike.sh:24–29` | Ошибка producer скрывается `tee`; stderr harness отбрасывается, classes общие со старым spike. Исправить exit propagation, fresh outputs и fault injection каждого шага |
| P1 | `OpenAiCompatSpike.scala:110–132,189–197,243–257` | Acquire без cancel hook, blocking readLine и await после timeout не доказывают ограниченный срок отмены. Нужны before-headers, no-newline/stalled-body и cleanup-deadline fixtures |
| P1 | `OpenAiCompatSpike.scala:159–197,237–241` | Malformed JSON игнорируется; EOF без подтверждённого terminal event может дать success. Нужны terminal-state machine и отрицательные stream fixtures |
| P2 / граница scope | `OpenAiCompatSpike.scala:33–37,99–108` | Request не содержит tools/tool-result roundtrip; URL всегда дополняется `/v1/chat/completions`. Сборка unsolicited delta не доказывает полноценный tool round или configurable base-prefix contract |

Последние три строки — source-level findings, без заявления о воспроизведённой сетевой ошибке. Dummy-key argv подходит только для этого fixture; production credentials должны поступать через env/helper без history/log exposure. Blocking cancellation «на границе строк» нельзя переносить в runtime как гарантию deadline. Приоритет этого spike не снимает обязательность прямого Anthropic-compatible adapter из целевого продукта.

## Что меняется в плане и промпте

1. Новый finish target — [working acceptance W01–W18](../working-product.md), включая sessions/context/instructions/SPI/package; M0 runtime demo недостаточно.
2. [Parallel runbook](../parallel-development.md): coordinator + до трёх workers, worktrees или immutable snapshot copies, один владелец shared contracts и интеграции.
3. [Stage-aware gates](../quality-gates.md): невозможно потребовать готовый full product от первого component diff; milestone requirements сохраняются.
4. [OpenCode finish prompt](../prompts/opencode-finish.txt) сначала проверяет текущий code/evidence, исправляет обнаруженные defects, затем ведёт параллельные waves до actual runnable product.
5. Mocks доказывают protocol/runtime behavior. Live working scenario и сравнение с OpenCode сохраняют отдельные статусы и требуют configured endpoint/budget.

Это корректировка программы исполнения. Она не означает, что перечисленные runtime defects или недостающие продуктовые функции уже реализованы/исправлены данным review.
