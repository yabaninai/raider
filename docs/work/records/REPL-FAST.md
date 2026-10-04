<!-- Work record. Original in Russian; key results in English below. -->

# REPL-FAST (W10-subset): work record

Status: done (fixture/mock backend; honest obligations listed). Date: 2026-10-03.
Baseline: root checkout без коммитов (все файлы staged, main без history);
предыдущий last-passing gate: artifacts/quality/20261003-151233 (board).

## Resulting behavior

`sbt --batch "raiderRepl/runMain raider.repl.Main"` поднимает интерактивный
REPL, в котором настоящий компилятор Scala 3.9.0 (pinned `scala3-repl_3` +
JLine 4.0.14) исполняет ввод в процессе:

- val/def/case class, импорты и многострочный ввод (продолжение по
  незакрытым скобкам/`=`; пустая строка всегда досылает буфер);
- ошибка компиляции СОХРАНЯЕТ bindings, prompt возвращается (W10-subset);
- фасад агентов: `scout.ask("...")`, `val j = worker.start("...")`,
  `j.await()` (идемпотентен), `j.cancel()`, `j.watch()` — поверх
  ScriptedModelBackend, БЕЗ сети; задел `j.send` — typed refusal RA-CAP;
- команды `:help :jobs :cancel <id> :reset :quit`;
- non-TTY режим: piped stdin читается напрямую (dumb-free pipe reader);
- PTY-режим: JLine line editor, Ctrl+C чистит pending-буфер.

Ключевые архитектурные находки интеграции (воспроизведены, не теория):

1. **Сплит статик-объектов**: in-process REPL classloader
   (`AbstractFileClassLoader`) грузит classpath-классы SELF-FIRST →
   `FacadeOps$` существует в ДВУХ экземплярах (мир консоли и мир
   интерпретатора). Поэтому всё REPL-состояние (сессия, registry job-хендлов)
   живёт в мире интерпретатора; консоль общается только через eval
   `ReplCommands.print*` (captured stdout = repr, resN-нумерация юзера не
   сдвигается). Проба: APP `FacadeOps$@6913c1fb` ≠ REPL `@5502f74c`.
2. **`:reset` = пересоздание драйвера**: state-only reset сбрасывает счётчик
   имён `rs$line$N`, но старые классы остаются в classloader'е → коллизии
   (после reset `scout.ask` возвращал `String = ()`). Новый `ReplDriver`
   приносит чистый namespace.
3. **sbt-ланчер съедает stdin**: homebrew-скрипт sbt на `--batch` делает
   `exec </dev/null` (строка 703) — piped input в принципе не доходит до JVM.
   Non-TTY acceptance идёт прямым `java -cp` (classpath через
   `sbt --batch --error "export raiderRepl/runtime:fullClasspath"`).
4. **Ctrl+C в JLine 4 не забинден** (abort по умолчанию на Ctrl+G; при ISIG
   `\x03` вообще превращается в SIGINT мимо keymap): продукт явно биндит
   `\u0003 → Reference("abort")`; PTY-тест выключает ISIG на slave, чтобы
   проверять именно продуктовый bind.
5. Тесты с реальным компилятором требуют `Test / fork := true`: внутри
   sbt-JVM `java.class.path` = только sbt-launch → `-usejavacp` слепнет.

## Changed paths

Новые модули (root aggregate):

- `modules/repl-engine/` — `ReplEngine` (frozen trait: eval/completions/
  reset, EvalResult enum) + `DottyReplEngine` (ReplDriver wiring, классификация
  Value/CompileError/RuntimeFailure, reset=rebuild) + `ReplEngineSpec` 6/6.
- `modules/repl/` — facade (`ReplSession`, `FacadeOps`: extension
  ask/start/await/send/cancel/watch на `using ReplSession`; `ReplCommands`:
  консольные text-entry points) + `Main` (pipe/tty loops, multiline, prelude,
  banner) + `Commands` (`:help/:jobs/:cancel/:reset/:quit`; `:type` — честный
  отказ) + `FacadeOpsSpec` 6/6 (sequential — общий глобальный registry).

Изменённые:

- `build.sbt`: raiderReplEngine (scala3-repl 3.9.0, jline-terminal/reader/jni
  4.0.14, `Test / fork`), raiderRepl (deps core+runtime+dsl+testkit+replEngine,
  `run / fork`, `Test / fork`); оба в root aggregate/dependsOn; zio-streams в
  raiderRuntime (zio-streams уже был транзитивным через zio-json в core).
- `modules/runtime/.../loop/Runner.scala`: ЕДИНСТВЕННАЯ точка исполнения
  `runText(backend, model, messages, ceilings, admission)` — admission slot +
  reserve (Unknown=unpriced, никогда free) → stream → collect TextDelta до
  Finished; Failed → typed error; нет Finished → StreamProtocol. Без tools/retries.
- `scripts/build/compile.sh`: + scala3-repl/jline jars (и compiler jars для
  tasty-ссылок) в compile-classpath gate compile-fixtures.
- `scripts/quality/repl_smoke.sh`, `scripts/quality/repl_pty_smoke.py`: новые
  acceptance-харнессы (см. evidence).

sha256 (финальные исходники):

```
fd495f13…  build.sbt
29c88ca2…  scripts/build/compile.sh
14382974…  scripts/quality/repl_smoke.sh
25a85825…  scripts/quality/repl_pty_smoke.py
72b3c6a0…  modules/repl-engine/src/main/scala/raider/repl/engine/DottyReplEngine.scala
799f4b7f…  modules/repl-engine/src/main/scala/raider/repl/engine/ReplEngine.scala
58db03fd…  modules/repl-engine/src/test/scala/raider/repl/engine/ReplEngineSpec.scala
88909526…  modules/repl/src/main/scala/raider/repl/Commands.scala
e8919b92…  modules/repl/src/main/scala/raider/repl/Main.scala
fdd2b577…  modules/repl/src/main/scala/raider/repl/facade/FacadeOps.scala
6dd07032…  modules/repl/src/main/scala/raider/repl/facade/ReplCommands.scala
99592566…  modules/repl/src/main/scala/raider/repl/facade/ReplSession.scala
d17289e1…  modules/repl/src/test/scala/raider/repl/facade/FacadeOpsSpec.scala
88338a6a…  modules/runtime/src/main/scala/raider/runtime/loop/Runner.scala
```

## Acceptance evidence

| Criterion | Actual | Evidence |
| --- | --- | --- |
| Фаза 0 (контракты компилируются) | sbt compile зелёный до старта агентов | sbt output, 15:37 |
| Agent-A: engine val/def/case class живут между eval; compile error сохраняет; reset чистит | 6/6 (после фиксов: DiagnosticHeader `[E###]`, fork, reset=rebuild) | `raiderReplEngine/test` ×N |
| Agent-B: facade ask/start/await/регистрация/cancel-терминал/send-отказ | 6/6 ×3 стабильных прогона (после `@@ TestAspect.sequential`) | `raiderRepl/test` |
| Полный sbt test | все модули зелёные (core 5+13+26, dsl 10, testkit 3, runtime 33, tools 19, engine 6, facade 6) | `sbt --batch test` |
| Quality gates (fast, full-tree режим) | 6/6 passed | artifacts/quality/20261003-204841/ |
| Non-TTY smoke: `printf 'val x = 1\nx\n:quit\n'` + ask/start/await/:jobs | exit 0; val x: Int = 1; res0: Int = 1; scripted answer; j_1 Succeeded; bye | scripts/quality/repl_smoke.sh → artifacts/repl-fast/nontty-transcript.txt |
| PTY smoke (реальный процесс java, не mock) | 13/13 checks, findings=[] | scripts/quality/repl_pty_smoke.py → artifacts/repl-fast/pty-summary.json, pty-transcript.txt |

Demo (non-TTY transcript, сокращено):

```
raider> val x = 1
val x: Int = 1
raider> x
val res0: Int = 1
raider> scout.ask("ping")
val res1: String = "[scripted-fixture] mock answer (ScriptedModelBackend; no live network in this slice)"
raider> val j = worker.start("bg")
val j: raider.runtime.jobs.JobHandle[String] = ...
raider> j.await()
val res2: String = "[scripted-fixture] mock answer ..."
raider> :jobs
j_1  Succeeded  worker: bg
raider> :quit
bye
```

## Commands

| argv | Exit | Log |
| --- | --- | --- |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch compile` | 0 | console |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderReplEngine/test"` | 0 (после фиксов) | console |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch "raiderRepl/test"` ×3 | 0,0,0 | console |
| `COURSIER_CACHE=/tmp/cc-master sbt --batch test` | 0 | console |
| `make quality-changed QUALITY_EXECUTE=1` | 0 | artifacts/quality/20261003-204841/manifest.json |
| `sh scripts/quality/repl_smoke.sh` | 0 | artifacts/repl-fast/nontty-*.txt |
| `python3 scripts/quality/repl_pty_smoke.py` | 0 | artifacts/repl-fast/pty-summary.json |

## Limitations / blockers

- **Live-wire не цель этого промпта**: только ScriptedModelBackend (fixture
  маркирован в banner и транскриптах). Провайдеры, streaming-коалесинг,
  journal — obligations, не «готово».
- `j.send` (job steering) — typed RA-CAP refusal, obligation в board.
- `:type`, completion (`completions()` = Nil), journal — obligations.
- Runner: без tools/retries (RAI-010 loop — следующий слой); ceilings
  параметр валидируется, но учёт ведёт admission (его limits).
- Эвристика multiline — char-count по скобкам (не лексер); строка с
  непарной скобкой внутри литерала досылается пустой строкой.
- `:reset` теряет доступ к ранее выданным job-хендлам в `:jobs` (fibers
  дорабатывают, но registry нового мира пуст — сообщение команды честно).
- Один sbt процесс за раз (build-lock); параллельные mutable builds
  запрещены — агентские прогоны сериализовались lock'ом.
- Инфра-факт: 1 из 2 Task-агентов (A) умер посреди стрима (локальный
  llama.cpp: "stream interrupted"); его черновик дописан координатором.
- Gate `core-contracts-spec`/`compile-fixtures`/`plan-validate` не
  перепривязаны к этому record'у задним числом: evidence 20261003-204841
  снята с финального дерева.

## Review and next task

Независимый review этой записи не проводился (координаторская сессия).
Следующие готовые слои: RAI-010.a native agent loop (freeze → Admission →
stream → tool rounds) поверх Runner.runText; live-wire transport — после
RAI-011/012; заголовок W-карточек — по docs/working-product.md.
