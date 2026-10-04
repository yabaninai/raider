<!-- Translated from Russian original. Key terms preserved as-is. -->

# ADR-015: RAI-001 feasibility — pinned toolchain и adapter findings

Статус: частично подтверждённые candidates, requires revision после independent review 2026-10-03. Исходная дата: 2026-10-02.
Evidence: `artifacts/rai-001/` (run `sh experiments/repl-spike/run_spike.sh`, exit 0, 32/32 checks).

Независимая [оценка](../reviews/2026-10-03-readiness.md) подтверждает compile/основную REPL feasibility, но выявляет false-green suite и непокрытые HTTP/process/exit гарантии. Решения ниже сохранены как исходные предложения: перенос HTTP cancellation, ThreadDeath и signal/report logic в production требует отрицательных fixtures и revised ADR.

## Контекст

RAI-001 требует actual compiler/PTY/headless proof, а не утверждения. Spike проверен на
host darwin/arm64 (macOS 26.5.1), JDK 20.0.1 (Oracle). Все версии ниже — фактически
использованные в прогоне; inventory с sha256: `artifacts/rai-001/toolchain.json`.

## Проверенные версии и факты

| Компонент | Версия | Наблюдение |
| --- | --- | --- |
| Scala compiler | `org.scala-lang:scala3-compiler_3:3.9.0` | единственная 3.9.x на Central; совпадает с планом-кандидатом |
| Scala REPL | `org.scala-lang:scala3-repl_3:3.9.0` | с 3.8+ отдельный artifact, НЕ входит в compiler jar |
| Scala library | `org.scala-lang:scala-library:3.9.0` | unified library; `scala3-library_3:3.9.0` — manifest-only relocation stub (344 B) |
| JLine | 4.0.14 (терминал/reader/jni) | зависимость scala3-repl_3 |
| ZIO | `dev.zio:zio_3:2.1.26` | unsafe.run/fork, fiber interrupt, onExit — работают на 3.9.0/JDK 20 |
| zio-json | `dev.zio:zio-json_3:0.10.0` | DeriveJsonCodec.gen, decode/roundtrip/negative PASS |
| JDK HttpClient | JDK 20.0.1 встроенный | streaming + fiber-interrupt закрывает body; сервер видит disconnect |

## Решения

1. **RAI-003 build** включает `scala3-repl_3` только в dev/compiler distribution;
   headless distribution = zio/scala-library без compiler/JLine
   (проверено: 0 загрязняющих jar в headless classpath, HEADLESS-04).
2. **Запуск compiler/REPL через `java -cp`** требует явного `-classpath`/`-usejavacp`,
   иначе `MissingCoreLibraryException`. RAI-003 sbt и RAI-019 launcher передают
   classpath явно; официальный `scala` launcher делает то же неявно.
3. **REPL interruption**: dotty REPL останавливает evaluation через `StopRepl`
   (`java.lang.ThreadDeath` в evaluating thread). ZIO `unsafe.run` это переживает:
   fiber прерывается, observer-cancel `fiber.await` НЕ отменяет наблюдаемый job
   (SPIKE-03-await-observer: prompt вернулся, `w.info` → state=running). Это ключевая
   feasibility-основа для facade-семантики из product-repl.md §10.
4. **HTTP client candidate** (runtime-contracts §1): JDK HttpClient + узкий ZIO
   async adapter — принят для RAI-011; wire parser отдельным классом.
5. **JSON codec candidate**: zio-json 0.10.0 — принят для versioned schemas RAI-002
   (schema-пары финализируются там на compile fixtures).
6. **PTY harness contract** (для RAI-004/019 repl profile): PTY обязан устанавливать
   `TIOCSWINSZ` (40×120), `TERM=xterm-256color` и отвечать на Primary DA
   (`ESC[c` → `ESC[?62;22c`), иначе JLine 4 не рисует промпт. Многострочный ввод
   REPL завершается пустой строкой. Ответы на capability-queries — часть harness,
   не продукта.
7. **Signals**: `sun.misc.Signal` (TERM/INT) → `System.exit(128+n)` + shutdown hook
   с bounded interrupt (4s grace) — проверено: 143/130, atomic write partial run.json,
   owned child убит, orphan по PID нет. RAI-022 runner сохраняет этот механизм
   (замена на platform-native — отдельной карточкой при необходимости).

## Дельты и ограничения

- **JDK 21 LTS на host отсутствует** (только 20.0.1 и 11.0.20). Spike проведён на
  JDK 20.0.1; RAI-003 bootstrap обязан поставить/запинить JDK 21 (Temurin) и
  перепрогнать compile smoke. Это ограничение evidence, не автоматический pass для 21.
- REPL печатает stacktrace `ThreadDeath` после Ctrl+C foreground — косметика для
  RAI-019 (adapter должен рендерить понятную отмену).
- Вывод background fiber интерливится с edit buffer (транскрипт подтверждает) —
  single-writer TerminalRenderer обязателен в RAI-019, как и предполагал контракт.
- JLine рисует серый `~` status-line артефакт поверх узких терминалов — зафиксировано
  как наблюдение.
- Windows/Linux suite не входили в spike (darwin/arm64 only).
