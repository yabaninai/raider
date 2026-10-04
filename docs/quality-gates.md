<!-- Translated from Russian original. Key terms preserved as-is. -->

# Quality gates и доказательства качества

## 1. Статус

В этом пакете gate names, команды и пороги — требования к будущему runner. Сейчас в Raider нет `Makefile`, sbt build или реализованных gates. RAI-003/004 создают их. Не запускать несуществующую команду и не записывать её как passed.

Обязательная философия: defect должен воспроизводиться без реальной LLM там, где можно задать scripted response. Модель оценивает решения, а machine checks проверяют эффект. Summary исполнителя не evidence.

Редакция 2026-10-03: применимость профиля определяется stage и именованными cases из protected registry. Уточнение ниже устраняет циклы «facade требует ещё не существующий packaged REPL» и «process tool требует будущий CI runner». Все финальные требования сохраняются; отсроченная проверка не считается passed.

## 1a. Activation stages и review gate policy

| Stage | Что допускает component acceptance | Что не может быть объявлено готовым |
| --- | --- | --- |
| bootstrap | Actual compiler/spike/build probes с exact argv/source/toolchain hash, пока runner создаётся | full/продуктовая suite |
| component | Active named cases для frozen interface и изменённого поведения; testkit/local mock допустим там, где проверяется собственная логика | Ещё отсутствующие end-to-end capabilities |
| milestone | Все обязательные cases целевого milestone на одной интегрированной сборке | Missing/not_due/skipped/unavailable required gate |
| working-product | W01–W17, full deterministic suite, real process/PTY/install/task corpus | Working claim по одному SDK/mock demo |
| release | Working plus advertised platform/package/security/integration capabilities | Непроверенный advertised backend/платформа |

RAI-004 создаёт reviewed registry: `gate_id`, `profiles`, `activation_stage`, `prerequisites`, `component_scope`, `required_by`, argv/tool prerequisites, expected artifact/check IDs. Stage manifest фиксирует выбранные cases и остающиеся obligations. `not_due` — pending obligation с target stage/owner, не test pass. Активная проверка с отсутствующим инструментом или неработающим test runner всегда fail/unavailable; stage не скрывает ошибку.

На component stage `repl` у RAI-018 означает compile/facade ownership fixtures; реальные compiler/PTY cases активируются у RAI-019/020 и обязательны к RAI-026. `ci` у process tools означает их headless/env/signal contract fixtures; complete bundle/context/reports/platform suite обязательна к RAI-026. Аналогично early eval — scripted loop/verifier cases, полный task corpus — к working-product. Docs сначала проверяет links/API compile fixtures; runnable examples активируются вместе с соответствующим API. Exact map фиксирует coordinator и fresh reviewer до назначения lane, не worker после неудачи теста.

Изменение build/dependencies/registry до M0 требует self/static/contracts/security и **всех уже активных затронутых cases**; ещё не созданный full product остаётся pending. После достижения M0 build change требует full. Unknown path требует conservative active suite и reviewed classification, не автоматический пропуск. `make quality-full` всегда строгая общая milestone проверка: пока продукт не готов, её нельзя переопределить как «только то, что умеем».

Целевые дополнительные команды runner: `make quality-profile QUALITY_STAGE=component PROFILES="..."` и `make acceptance-working`. Stage/registry evidence обязательны; эти команды пока не реализованы. Minimal development CI после foundation выполняет active compile/unit/contracts/self/risk cases на каждом integration, не ждёт позднюю RAI-045 packaging matrix. Полные gates снова выполняются на интегрированном candidate после waves.

## 2. Команды после реализации runner

```sh
make quality-fast
make quality-changed
make quality-changed QUALITY_EXECUTE=1
make quality-full
make quality-profile PROFILES="ci contracts runtime"
make quality-integration
make quality-release
python3 scripts/quality/quality.py evidence artifacts/quality/<run-id>/manifest.json --require full
```

`quality-profile PROFILES="..."` выполняет union named profiles из protected registry; unknown profile error, self обязательно. `quality-changed` без EXECUTE только показывает классификацию. `full` включает local deterministic integration/PTY, но не live paid providers и не внешний Yabanin Docker deployment. `quality-integration` — explicit external stand gate с prerequisite checks; отсутствие required stand — failed/unavailable, не green skip.

PR/CI использует merge-base ref `QUALITY_BASE`; чистый checkout не означает «нет проверок». Local command без base учитывает staged/unstaged/untracked/deleted/renamed; clean checkout выполняет минимум fast, CI всегда передаёт base или full. Initial commit/no HEAD — classifier поддерживает пустое baseline дерево.

Snapshot lanes могут не содержать `.git`. RAI-004 реализует second baseline source: explicit immutable inventory manifest (`QUALITY_BASE_MANIFEST` — целевой интерфейс). Diff учитывает add/modify/delete, mode и symlink target; rename допускается как delete+add. Проверяются hash самого baseline manifest и before/current source; отсутствующий или несоответствующий baseline — error. Чистая копия выполняет active fast, неизвестный путь — conservative active suite. Это тот же classifier/evidence contract, не второй урезанный runner. Bootstrap до реализации manifest mode выполняет exact scoped checks и сохраняет inventories. Проверка `git status` заменяется явным `Git unavailable: isolated snapshot` и сверкой manifest; нельзя молча запускать checks в root checkout.

## 3. Профили

| Профиль | Содержание | Где обязателен |
| --- | --- | --- |
| self | Tests quality runner, command registry, fingerprint и evidence | Любой code gate |
| static | scalafmt check, compiler warnings, выбранные Scalafix rules, forbidden API rules | Все Scala/config изменения |
| unit | Unit/property tests core/runtime/parser/config | Все behavior изменения |
| contracts | Public compile examples, negative type tests, JSON/event/backend schema conformance | Public API/schema/protocol |
| runtime | Cancellation, ownership, races, root budget, queue/mailbox/recovery invariants | Runtime/concurrency |
| transport | Real local HTTP server, fragmented SSE, tool calls, retries/cleanup | Network/providers |
| ci | Non-TTY, signals/exits, bundle isolation, context/trust, artifact/JUnit schemas, adapters | Headless runner/CI/checks |
| repl | Реальный pinned compiler, PTY signals/paste/completion, terminal rendering | CLI/REPL/compiler |
| tools | Workspace/process/permission tests и mutation preconditions | Tools/policies |
| docs | Links, code examples через compiler/mock runner, command freshness | Docs/examples/public API |
| security | Dependency vulnerabilities, secrets, policy boundary checks, license inventory | Dependencies/auth/tools/process/network |
| compatibility | Native/PI/OpenCode backend contract suites, pinned corpus | Importers/bridges |
| eval | Deterministic protected acceptance verifiers | Agent loop/profiles/model defaults |
| performance | Runtime overhead, background spawn latency, soak/memory/queue bound | Resource/concurrency/performance |
| integration | Yabanin stand, session keys/labels/accounting, optional live bridges | Release integration readiness |
| release | Packaged JVM distribution smoke, clean install, SBOM/notices/platform evidence | Release |

`fast = self + static + scoped unit + minimal contracts`; `full = self/static/unit/contracts/runtime/transport/ci/repl/tools/docs/security/eval + compatibility для включённых adapters + performance smoke`. `release` добавляет integration по declared supported capabilities. Реальные LLM отдельно, never implicit в full.

## 4. Инструменты и версии

RAI-003 закрепляет sbt, Scala, JDK major/build policy, ZIO и test library versions. Formatter/linter/scanner exact versions и tool downloads отделены от gate execution. `make bootstrap` устанавливает согласованный toolchain, gate не скрывает установку пакетов посреди проверки.

Static: `sbt scalafmtCheckAll`, `sbt 'scalafixAll --check'` если pinned syntax поддерживается, compilation с deprecation/feature/unused checks и fatal warnings на owned code. `scalafixOnCompile` выключен: check не должен незаметно переписать исходники.

Forbidden rules: `Thread.stop`, unbounded `Await.result`, global unsafe runtime launch вне sanctioned facade, broad catch подавляющий interruption, unbounded queues в runtime, Double money arithmetic, raw `Runtime.exec`/shell concatenation вне process adapter, secret logging. Rules привязаны к реальным рискам; допустимые low-level exceptions узкие и документированы.

Security dependency scanning: pinned OWASP Dependency-Check или другой подтверждённо работающий JVM scanner над resolved classpath/build plugins; precise analyzer/tool выберет RAI-004. Ошибка базы/scanner не passed. SBOM/attributions включают runtime и compiler/JLine transitive artifacts. CVE false positives получают bounded owner-approved exception с reason/expiry; модель не создаёт собственное разрешение.

License policy proposal согласовать до distribution: разрешённые SPDX expressions + inventory notices. Не наследовать лицензию продукта автоматически из pi/OpenCode. Копированные upstream artifacts сохраняют license/origin/commit/hash.

## 5. Evidence manifest

Минимальные поля:

```json
{
  "schema_version": 1,
  "run_id": "qg-example",
  "status": "passed",
  "source": {"commit": "optional-on-initial-repo", "sha256": "..."},
  "policy_sha256": "...",
  "toolchain": {"scala": "pinned", "jdk": "pinned", "sbt": "pinned"},
  "profiles": ["static", "unit"],
  "gates": [
    {"id": "compile", "argv": ["sbt", "compile"], "exit_code": 0,
     "status": "passed", "log": "compile.log", "log_sha256": "..."}
  ],
  "test_summary": {"passed": 0, "failed": 0, "skipped": 0},
  "started_at": "...",
  "finished_at": "..."
}
```

Этот JSON — schematic, не реальное evidence. Runner заполняет числа по machine-readable результатам.

Fingerprint: tracked + nonignored untracked product source/config/specs/prompts, deletion/path/mode/symlink target; exclude `.git`, build outputs, artifacts и runtime `.raider` state по protected policy. Точная operational allowlist: `docs/work/board.md`, `docs/work/records/**`, `docs/work/assignments/**`; их hashes/revisions сохраняются как audit metadata и проверяются docs lint отдельно. Нельзя складывать туда executable fixtures/config или расширять исключение до всего docs. Exact resolved dependency inventory/build/toolchain hash сохраняются отдельно. Source snapshot lane и integrated candidate различаются; чужой manifest не перепривязывается к новому hash.

Hashes до/после различаются → stale. Изменился command registry, threshold policy, fixture corpus или лог → evidence invalid. `evidence --require PROFILE` проверяет статус, completeness, required gates, current hashes и logs; fast не может выдаваться за full.

Runner исполняет argv из protected registry без shell. Fixture/task card не расширяет registry и не вставляет команду `curl ... | sh`. Timeout/interrupt продолжают сохранять partial report, общий exit nonzero; SIGKILL оставляет running, который не принимается за success. Evidence write failure не даёт зелёный результат.

## 6. Матрица по изменениям

| Изменение | Минимальные profiles |
| --- | --- |
| Core/DSL/public API | static/unit/contracts/docs |
| Runtime/jobs/budgets/mailbox | static/unit/contracts/runtime/eval |
| Provider/HTTP/SSE | static/unit/contracts/transport/security |
| Headless CLI/bundle/CI/checks/reporters | static/unit/contracts/ci/runtime/docs/security |
| REPL/CLI/JLine/compiler integration | static/unit/contracts/repl/docs/security |
| Tools/workspaces/process | static/unit/tools/runtime/security |
| Yabanin adapter | static/unit/contracts/transport/security + integration acceptance |
| Dependency/build/runner/policy | До M0: все active affected cases + security/self/contracts; после M0: full. Gate-policy independent review обязателен |
| Agent prompt/model-role defaults | docs/eval/compatibility; paid comparison только explicit |
| Docs с DSL examples | docs/contracts; после REPL реализации ещё selected repl |
| Unknown path/file type | Conservative active suite + reviewed classification; на milestone full. Не skip |

Классификация — нижняя граница. Семантический риск карточки может добавлять profiles. Нет правила «измени только README, чтобы обходить eval»: prompts/configuration classified отдельно.

## 7. Обязательные runtime invariants

Каждый сценарий имеет короткий test ID и deterministic fixture:

1. Task construction/map/printing не вызывает provider/tool.
2. Два `run(task)` дают два roots; два `await(job)` не повторяют effects.
3. Concurrent waiters получают один consistent outcome.
4. Parent cancellation прерывает subtree и очищает reservations/permits.
5. Cancellation в момент spawn registration не оставляет orphan.
6. Success/cancel race даёт один terminal transition.
7. maxConcurrentLlm=1 + waiting parent не deadlock.
8. Суммарные root attempts/children/depth не превышают caps при fanout.
9. Reservation/settlement при failure, unknown usage и overflow корректны.
10. Deadline отменяет queue wait и retry sleep; cleanup bounded.
11. Output repair учитывается как attempt и не бесконечен.
12. Streaming partial tool args никогда не execute.
13. Message consume/cancel/finish race не теряет receipt.
14. Scope exit cancels unjoined children; `all/batch` waits results.
15. Slow observers не блокируют critical state/accounting; queues bounded.
16. External backend без cancel acknowledgement не получает Cancelled ложным образом.
17. Reload не меняет prompt/tools running job.
18. Crash archive не replay мутацию автоматически.
19. Parent/child capabilities только сужаются.
20. Journal error запрещает false durable-success claim.

Property/state-machine tests генерируют последовательности start/fork/send/cancel/join/fail/close. Virtual clock контролирует backoff/deadline; реальные sleeps в unit tests запрещены, кроме явно platform/PTY tests. Deterministic ordering через promises/barriers, не «подождать 100ms».

## 8. Настоящие REPL acceptance tests

PTY tests запускают packaged `raider repl --mock` как процесс с терминалом, отправляют ввод/signals и проверяют transcript/state:

- preset agent `.ask` → результат usable без создания agent;
- `.start` двух jobs → следующий `val` исполняется до завершения jobs;
- `(scout andThen reviewer).ask` → typed stages в правильном порядке;
- `all` для Programs/Tasks, `batch` → tuple/order/concurrency preserved;
- compile error → ранее созданный binding остаётся;
- runtime error → prompt восстановлен, runtime жив;
- multiline/paste/Unicode/quoted path;
- completion основных symbols;
- foreground Ctrl+C cancels children;
- awaiting existing job Ctrl+C оставляет job running;
- watch Ctrl+C отписывает observer;
- idle Ctrl+C очищает строку;
- EOF/:exit/reset clean shutdown;
- background output не портит edit buffer;
- stdout JSON не содержит ANSI/logs/secrets;
- load file/register/run и такой же file через CLI;
- pretty-print Job/Task не запускает эффект и не раскрывает credential;
- bare Task печатает hint `.run()/.start()`;
- dot/infix syntax используют один launch implementation.

Mock `Interpreter.evaluate` полезен для unit test, но не заменяет эти tests. PTY helper выбирается RAI-001; допускается небольшой Python stdlib helper Unix, platform-specific Windows coverage отдельна. macOS+Linux обязательны для v1; Windows заявлять supported только после настоящей suite.

## 9. Coverage, mutation и качество tests

Начальные release thresholds: overall statements >=80%, core/runtime/provider parser >=90%, critical branch cases через явную invariant matrix. Coverage не заменяет проверку инвариантов и не требует бессмысленных tests DTO/getters.

Coverage compiler module отдельно: adapter может иметь меньшую instrumentable долю, но обязательны PTY/compile acceptance. Исключения generated/external code — точные и reviewed. Не менять threshold вместе с исправлением implementation.

Selected mutation probes для budget/cancellation/protocol/policy: удалить decrement permit; заменить unknown cost на zero; исполнить partial args; позволить child broaden permission; сохранить state после failure как success. Suite обязана поймать каждую такую мутацию. Не внедрять тяжёлый общий mutation framework до доказанной пользы.

Tests, которые утверждают только структуру implementation или мокают единственный проверяемый эффект, не считаются достаточной приёмкой. Regression test сначала воспроизводит bug и затем проверяет resulting behavior.

## 10. Performance и UX budgets

Целевые начальные acceptance budgets на declared reference machine, после прогрева:

- native background submission p95 <=100ms, исключая Scala compilation/network queue execution;
- idle prompt остаётся редактируемым при 20 streaming mock jobs;
- mock native cancellation acknowledgement p95 <=500ms, process/network grace отдельно;
- 1,000 short mock jobs в bounded batches не оставляют registry/fibers/processes; memory после cleanup стабилизируется по baseline;
- journal flush/slow renderer не создают unbounded backlog;
- REPL cold start измерен и показан, initial target <=5s packaged warm-cache, dependency downloads исключены и отдельно видны.

RAI-001/043 измеряют feasibility и нагрузку. Любое изменение budget требует recorded decision с измерениями; исполнитель не ослабляет порог ради pass. Flaky performance check не использовать как unit assertion на произвольном shared CI: benchmark lane или relative regression против calibrated baseline, сохранять machine/JDK/load metadata.

## 11. Agent quality eval

Начальный набор: минимум 12 deterministic mock cases для loop/protocol/tools и 6 маленьких реальных task fixtures (repo search, structured extraction, safe edit, test-driven bugfix, review with planted bug, parallel synthesis).

Protected verifier вне mutable agent workspace. Для coding — hidden regression tests и diff constraints; для research — source presence/consistency checks, manual rubric для неподдающихся формализации выводов. Safety/protocol hard gates отделены от usefulness score.

Live model comparison: same tasks/tools/budgets/seeds where supported, multiple repetitions, cost/latency/uncertainty recorded. Минимум 3 repetitions для pilot; выводы о стабильном превосходстве требуют достаточной выборки/CI, не одного удачного ответа. Eval holdout не включать в agent context.

## 12. Product CI и development CI

Product `ci` profile выполняет все 12 acceptance clauses [CI runtime](ci-runtime.md), в том числе noTTY/SIGTERM/fork trust и artifact-failure precedence. Direct transport acceptance не запускает Yabanin: environment без YABANIN_* и оба mock HTTP wires. Mixed-provider children делят root budget; cross-protocol history/tool IDs сохраняются. Package smoke проверяет отсутствие compiler/JLine в headless classpath, network dependency resolution и работу container PID1 signal forwarding.

Development CI самого Raider:

PR: pinned toolchain, format/compile/unit/contracts + risk profiles, без секретов на fork PR. Main/nightly: full на Linux, REPL/package smoke macOS, dependency scan. Release: full + declared optional integrations (Yabanin readiness требует pinned stand) + SBOM/notices + clean install + signed/checksummed distribution по release policy.

CI нельзя объявить работающим по наличию YAML. Проверить jobs на чистом checkout и сохранить logs/artifacts. Cache ускоряет сборку, не сохраняет pass без fingerprint.

Reviewer: смотрит actual diff, контракт, negative cases, logs/manifest и tests на нужной версии. Task done только когда card acceptance + gates passed. Blocker перечисляет точную missing dependency; skipped profiles остаются pending.
