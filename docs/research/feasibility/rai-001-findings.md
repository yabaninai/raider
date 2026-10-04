<!-- Translated from Russian original. Key terms preserved as-is. -->

# RAI-001 feasibility findings

Исторические findings первого прогона. Актуальный verdict и независимые повторения: [review 2026-10-03](../../reviews/2026-10-03-readiness.md). Полная карточка changes-required; цифра 32/32 сама по себе недостаточна из-за false-green entrypoint.


Дата: 2026-10-02. Прогон: `sh experiments/repl-spike/run_spike.sh` → exit 0,
32/32 checks (7 headless + 25 REPL). Все артефакты: `artifacts/rai-001/`.

## Измерения (host darwin/arm64, macOS 26.5.1, JDK 20.0.1)

| Метрика | Значение | Где |
| --- | --- | --- |
| REPL cold start до первого промпта | 1.15–1.62 s (3 прогона) | repl-summary.json |
| REPL peak RSS (с compiler+JLine) | ~547–667 MiB | repl-summary.json |
| Headless cold start (нормальный сценарий 2.5 s работы) | ~3.0 s wall | headless-harness.log |
| Headless peak RSS (ZIO runtime, без compiler) | ~103 MiB | headless-harness.log |
| Background start → возврат промпта | 0.34–0.46 s | SPIKE-01-start-nonblocking |

## Подтверждённое поведение (ключевые транскрипты)

1. **Foreground Ctrl+C** (`blocking(15000)`): REPL печатает
   `Attempting to interrupt running REPL command`, бросает `ThreadDeath` через
   `dotty.tools.repl.StopRepl` в evaluating thread; ZIO fiber прерывается;
   prompt возвращается ~2 s; bindings сохранены (`postFg: Int = 1` PASS).
   — repl-transcript.txt.
2. **Await Ctrl+C**: `w.await` прерван тем же механизмом, но job-волокно живёт
   (`w.info` → `state=running` PASS); явный `w.cancel()` → `state=interrupted` PASS.
   Разделение observer/owner cancellation подтверждено на настоящем REPL.
3. **Compile error сохраняет bindings**: `val bad: Int = "oops"` → 1 error,
   затем `good * 6` → `42` PASS; общий runtime-объект жив (`j.info` после ошибки).
4. **Многострочный ввод**: завершается пустой строкой (`val multi = 1 + 2` → 3).
5. **Unicode**: `val uni = "проект ✓"` round-trip в REPL PASS.
6. **Headless SIGTERM**: exit 143, `status=interrupted`, `signal=TERM`,
   `stepsCompleted=4` (truthful partial), child убит (`childAliveAfterCleanup=false`),
   orphan по PID нет. SIGINT: exit 130. Нормальный путь: exit 0, `succeeded`.
7. **Classpath split**: headless classpath (11 jar) не содержит
   scala3-compiler/scala3-repl/JLine (HEADLESS-04 PASS).
8. **HTTP**: JDK HttpClient streaming; fiber-interrupt через 900 ms закрывает body —
   python slow-server зафиксировал `client disconnected` (probe-http-cancelled.log,
   http-server.log).
9. **zio-json**: decode/roundtrip/negative — 3/3 PASS (probe-json.log).
10. **Missing toolchain**: REPL без library jar падает `NoClassDefFoundError`
    c exit 1 — явная недоступность, не молчание (repl-missing-toolchain.log).

## Ограничения / открытые вопросы для следующих карточек

- JDK 21 LTS не установлен на host: RAI-003 ставит и пинит Temurin 21, compile smoke
  повторяется там (см. ADR-015).
- Вывод background jobs интерливится с edit buffer → single-writer renderer (RAI-019).
- `sun.misc.Signal` — jdk.unsupported API: достаточно для MVP runner, но
  platform-контракт фиксируется в RAI-022.
- REPL eval не изолирован от ThreadDeath для произвольного user-кода — уже отражено
  в product-repl.md §10 (обещаний сохранить bindings после hard stop нет).
- PTY-harness требования (winsize/TERM/DA-ответы) переносятся в repl profile RAI-004.
