# RAI-001 repl/headless spike

Feasibility-доказательство RAI-001 (не production-код). Один общий ZIO runtime
(shared `RaiderSpike`) грузится в настоящий Scala 3.9.0 REPL из compiled classpath;
headless-раннер и probes работают на classpath без compiler/JLine.

## Запуск (полный suite)

```sh
sh experiments/repl-spike/run_spike.sh          # fetch + compile + оба harness
sh experiments/repl-spike/run_spike.sh --skip-fetch
```

Выход 0 ⟺ все 35 проверок проходят (10 headless + 25 REPL; F0-remediation
добавила grandchild-репродюсер, artifact-failure exit 27, before-headers cancel).
Evidence пишется в `artifacts/rai-001/` (git-ignored; override через
`RAIDER_SPIKE_EVIDENCE`): transcripts, summaries, `*.raw` логи шагов,
`toolchain.json` (координаты+sha256), source manifest внутри `environment.raw`.
Fault-injection self-test: `run_spike.sh --self-test-fault-injection`.

## Состав

| Файл | Назначение |
| --- | --- |
| `fetch_toolchain.py` | POM-walking resolver по repo1.maven.org, только pinned корни |
| `prelude/SpikePrelude.scala` | shared runtime: foreground `ask`/`blocking`, background `start`→`Job(await/cancel/info)` |
| `headless/HeadlessSpike.scala` | non-TTY раннер: root fiber + owned child process, SIGTERM/INT → bounded cancel + atomic partial artifact + 143/130 |
| `probe/HttpJsonProbe.scala` | JDK HttpClient + ZIO adapter (полное чтение и fiber-interrupt), zio-json derive/roundtrip/negative |
| `harness/repl_pty_harness.py` | PTY-драйвер настоящего REPL: SPIKE-01/02/03 (см. repl-summary.json) |
| `harness/headless_harness.py` | HEADLESS-01..04 + PROBE-* (см. headless-summary.json) |

Контекст и решения: `docs/adr/ADR-015-rai-001-feasibility-toolchain.md`,
`docs/research/feasibility/rai-001-findings.md`, work record `docs/work/records/RAI-001.md`.

## Важные детали запуска REPL на «голом» java

- `dotty.tools.repl.Main -usejavacp` обязателен (иначе `MissingCoreLibraryException`).
- PTY: нужен `TIOCSWINSZ` (например 40×120), `TERM=xterm-256color` и ответ на
  Primary DA `ESC[c` → `ESC[?62;22c`, иначе JLine 4 не отрисует промпт.
- Многострочный ввод завершается пустой строкой.
