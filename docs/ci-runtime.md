<!-- Translated from Russian original. Key terms preserved as-is. -->

# Raider в CI: главный контракт продукта

Все API/команды ниже проектируются, а не реализованы. Документ уточняет [DSL](product-repl.md) и имеет приоритет для headless invocation. Все три сценария входят в scope: review PR/MR, подготовка patch с проверками, произвольные research/generation/check workflows.

## 1. Граница с orchestrator

Raider исполняет один агентный workflow внутри одного CI job. GitHub/GitLab/другой orchestrator управляет DAG между jobs, runner allocation, secrets, matrix, manual approvals, artifact upload и retry job. Raider управляет детьми внутри своего процесса, инструментами, deadline и локальным root budget. Не нужен второй GitLab/GitHub scheduler.

Один Program запускается из REPL и compiled bundle. `pipeline[I,O]` — фабрика Workflow с versioned input/output codecs, требуемыми checks и declared success policy, а не отдельный runtime. Она доступна в MVP вместе с `workflow`; typed agents с schema repair добавляются в beta. В M0 schema decoding входа/результата и deterministic checks уже обязательны.

## 2. Удобный DSL

В файле definitions:

```scala
import raider.dsl.*

val inspect = (agents.scout andThen agents.reviewer).named("inspect")
register(inspect)

val audit = pipeline[String, String]("audit"): request =>
  for
    observations <- all(agents.scout(request), agents.reviewer(request))
    report <- agents.writer(s"$request\n${observations._1}\n${observations._2}")
    _ <- verify("report-format", report)
  yield report
register(audit)
```

`pipeline` требует implicit Codec[I]/Codec[O] и immutable DefinitionContext. `verify(name, output):Task[Unit]` вызывает trusted registered verifier с declared input codec; fail возвращает structured CheckFailed, без success-строки от модели. Runtime сохраняет check evidence вне мутируемого agent workspace. `check(name):Task[CheckResult]` запускает заранее зарегистрированную детерминированную проверку workspace; произвольная command string от модели здесь запрещена. Если check fail не преобразован в explicit advisory policy, root неуспешен. Registry фиксируется до dispatch.

Локально:

```scala
audit.ask("Проверь изменение")
val job = audit.start("Проверь изменение")
job.await
```

В library/CI исполняется `audit(input):Task[String]` через Runner; blocking REPL facade не импортируется. Composition, child ownership, tools, policy и output codec те же.

Review получает ChangeSet/diff context, результат — findings с severity/path/evidence. Patch workflow получает Spec, возвращает patch artifact и фактические checks. Research получает Question, возвращает output artifact и source references. Domain codecs и examples добавляются отдельными карточками; sketch ниже предполагает созданные доменные типы:

```scala
val repair = pipeline[Spec, Patch]("repair"): spec =>
  for
    plan <- planner(spec.prompt)
    patch <- patcher(plan)
    _ <- verify("allowed-diff", patch)
    tests <- check("regression")
  yield patch
```

`tests` — evidence реальной проверки. В MVP user-facing string presets остаются удобными, доменный `patcher:Program[String,Patch]` создаётся с output codec; typed model repair — RAI-028. `.ask/.start`, `andThen/all/batch`, `scope/fork/join` остаются единственными execution/composition verbs. Не вводить второй язык stage/job/YAML для внутренних агентов.

## 3. CLI и сборка

Для обычной задачи предусмотрен также `raider run --agent worker --input "..."` через общий Runner; mutually exclusive с bundle/workflow path. Уточнение UX и обязательной готовности: [working product](working-product.md).

```text
raider compile workflows.raider.scala --output pipelines.jar

raider run --bundle pipelines.jar --workflow audit \
  --input "Проверь проект" --ci generic --context ci-context.json \
  --profile yabanin --policy ci-readonly --out artifacts/raider

raider run --bundle pipelines.jar --workflow review \
  --input-json input.json --ci auto --policy ci-readonly --out artifacts/raider
```

`--input` только для string codec, `--input-json` читает ограниченный versioned JSON; взаимоисключение проверяется до provider call. Не читать stdin по умолчанию. `--mock` заменяет backend fixtures и явно сохраняется в artifacts. `--out` обязателен в CI, вне CI имеет documented default. Не угадывать workflow при нескольких registrations.

Два distribution: `raider-run` содержит core/runtime/headless CLI, `raider-dev` добавляет compiler/REPL/JLine. Production runner не подтягивает compiler и не разрешает зависимости из сети во время запуска. Source shorthand `raider run workflows.raider.scala ...` допустим только в dev distribution и явно сообщает compilation phase.

Bundle manifest: schema, Raider API/Scala/JDK compatibility, registered names/codecs, source/dependency/toolchain fingerprint, profile/verifier declarations. JAR SHA-256 проверяется до load. Bundle — исполняемый доверенный JVM code, hash подтверждает целостность относительно trusted expected hash, а не доверие к неизвестному источнику. Pin artifact digest и provenance в pipeline config; artifact из fork не становится trusted bundle.

Preflight order: arguments → bundle/context/input schema → trust/policy/capabilities → output-directory writable/space probe → provider profile/credentials → root creation/dispatch. Unsupported required capability блокирует запуск до spend. Reporter сохраняет preflight failure по возможности; отсутствие root обозначается null, не fabricated job.

Invalid output-path syntax/config — 21; filesystem permission/space/write failure обязательных artifacts — 27, включая preflight write probe. Probe не гарантирует, что диск останется доступным: final writes тоже проверяются. `--profile` выбирает named role bindings/defaults; resolver по [provider contract](provider-protocols.md). Explicit Agent provider/model pair сохраняется; роли разрешаются в выбранном profile, каждый resolved provider фиксируется до dispatch. Mixed-provider workflow не меняет endpoints после launch скрыто.

## 4. Нормализованный CI context

Versioned schema: `schemaVersion`, `system`, `instanceId`, `repositoryId`, `repositoryUrl`, `pipelineId`, `jobId`, `jobName`, `matrixKey`, `attempt`, `eventType`, `headSha`, `baseSha`, `ref`, `changeRequestId`, `runnerIdentity`, `declaredTrustLevel`. Required generic minimum: system/instance/repository/pipeline/job/attempt/trust; optional values nullable, не пустые вымышленные IDs. Identity fields bounded/validated, labels subset отдельно.

`--ci auto` выбирает platform adapter по environment и при конфликте требует explicit выбор. Env detection не повышает trust. `declaredTrustLevel` задаётся trusted owner policy, repository input не может объявить себя protected. SHA/diff получаются из подтверждённого checkout/context и сохраняют provenance; no blind interpolation PR title/ref в shell.

Каждая invocation получает новый RootId. Logical key `(instance, repository, pipeline, job, matrix, attempt)` связывает аналитику. `GITHUB_JOB` недостаточен для различения matrix entries; matrixKey передаётся явно. Retry создаёт новый execution и расход; logical key не является exactly-once token.

## 5. Policy и исполнение repository code

`ci-readonly` ограничивает model-directed writes/publish/network/tools. Для patch scenario owner выбирает `ci-patch` с workspace/diff bounds. Commit/push/PR-comment/deploy не часть baseline; publisher отдельно, с credentials и approved actions из CI configuration.

Pipeline bundle, policy и верifiers берутся из protected/pinned источника, а не переписываемого branch workspace. Agent output/diff и repository instructions — недоверенные данные. Model не может менять success policy, artifacts writer, verifier executable или ceiling budget.

Запуск тестов repository исполняет его код. Untrusted code должен выполняться в credential-free изолированном worker/container без доступа к runner/gateway secrets, host mounts и privileged socket. Stripped env полезен, но не является host sandbox. Process tool без sandbox capability не допускается для required-untrusted-execution profile. JVM plugin/Scala bundle имеет полномочия Raider process: никакой иллюзии, что tools policy ограничивает произвольную Scala.

Fork PR pipeline не получает provider/publish secrets. Для free deterministic preview доступен mock. Live review fork data допускается только через отдельный owner-controlled service/job с trusted code и isolated data path. Не исполнять fork scripts рядом с privileged `pull_request_target` credentials. Основание: [GitHub security guide](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target).

## 6. Без терминала, cancellation и deadline

Headless mode не спрашивает вопросы, не требует TTY и не блокируется на stdin. Policy `ask` превращается в NeedsApproval; owner может организовать manual gate снаружи и запустить новую invocation с narrowed approval capability. Результат ожидания не разрешает действие автоматически.

SIGINT/SIGTERM прекращает admission и отменяет owned subtree, HTTP streams и process groups. Cleanup имеет bounded grace; interrupted outcome/accounting сохраняются best effort. Launcher/container forwarding проверяется на настоящем процессе. Internal deadline меньше CI timeout с запасом на cleanup/artifact upload. Параметры grace/deadline validate до dispatch.

SIGKILL, OOM и удаление runner не позволяют гарантировать финальные artifacts. Periodic journal/progress полезны, но отсутствие complete result всегда incomplete. CI повторяет только допустимую task policy: mutation/Uncertain outcome не replay автоматически. Общий cap между matrix jobs потребует shared authority; local root limit не даёт pipeline-wide hard cap.

## 7. Артефакты

| Файл | Контракт |
| --- | --- |
| `run.json` | schema/RootId/context/bundle-policy hashes/execution/verdict/accounting/timestamps/errors |
| `result.json` | validated output или structured failure; output codec/version; model output ≠ verifier verdict |
| `events.jsonl` | sanitized bounded versioned lifecycle/tool/check events; stream writer |
| `checks.junit.xml` | реальные executed checks: pass/fail/error/skipped, stable IDs и escaping |
| `summary.md` | компактный human report: outcome/findings/patch/checks/usage uncertainty |
| `artifacts.json` | manifest output files: sha256/mediaType/size/provenance; own file исключён из self-hash |

Optional: `patch.diff`, SARIF schema-validated findings, `usage.json`, agent-eval directory. JUnit пустой suite не изображает проверку task correctness; minimal schema check отражён как настоящий check, skipped явно отмечен. Patch применяется только explicit downstream step после проверки hash/baseSha.

Atomic temp+rename для complete JSON; interrupted JSONL tail recovery documented. Reporter имеет size/path bounds, XML/Markdown sanitization, secret redaction и не выполняет строки output. Не включать raw env, токены, full prompts/tool args по умолчанию. Event truncation/overflow отражается в evidence; обязательные checks нельзя потерять тихо. Disk failure → nonzero, не green без artifacts.

`executionStatus`, `taskVerdict`, `safetyVerdict`, `protocolVerdict`, `accountingStatus` — отдельные поля. Exit определяется declared pipeline policy: advisory review может успешно создать отчёт с findings, blocking review завершится task-failed при превышении threshold. Policy и threshold frozen/provenance записаны. Никаких «agent сказал готово → exit 0».

## 8. Стабильные exit codes

| Code | Причина |
| --- | --- |
| 0 | Declared success policy выполнена, cleanup и обязательные artifacts завершены |
| 10 | Task/verifier/check rejection |
| 20 | Runtime/provider/protocol failure |
| 21 | Config/input/bundle/compilation/preflight-invalid |
| 22 | Policy/capability denial |
| 23 | Budget exhausted/required hard cap unavailable |
| 24 | Internal deadline exceeded |
| 25 | NeedsApproval в noninteractive mode |
| 26 | Side effect/cancellation outcome uncertain |
| 27 | Artifact/evidence write failure |
| 130 / 143 | SIGINT / SIGTERM handling |

При нескольких причинах записать primary и secondary errors. Signal code сохраняется при signal shutdown; иначе artifact failure 27 имеет приоритет, затем uncertainty 26, затем причина root stop. Wrapper обязан сохранить дочерний code, если не документирована отдельная accounting policy. No catch-all exit 0.

## 9. Тонкие адаптеры платформ

GitHub: normalize context; safe summary в `GITHUB_STEP_SUMMARY`; только allowlisted scalar outputs через `GITHUB_OUTPUT` с корректным framing. Raw model text не превращать в workflow commands `::...`. Default permissions read-only; SARIF upload/PR publisher отдельные optional credentials/capabilities. Reusable workflow/action — wrapper вокруг CLI, pin dependencies/actions SHA. Механизм summary/output описан в [официальной документации](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-commands).

GitLab: normalize `CI_*`; template с `artifacts:when:always`, paths и `reports:junit`. Protected/masked variables — credentials channel; никогда secrets в dotenv artifacts. `retry`, `interruptible`, `resource_group` — параметры orchestrator, а не Raider API. Tier-specific MR widgets optional; generic files/JUnit остаются core. Поддерживаемые report types: [GitLab docs](https://docs.gitlab.com/ci/yaml/artifacts_reports/).

Другие pipelines: explicit generic context JSON/CLI, output directory и exit code. No hardcoded runner paths, dependency on Docker-in-Docker, GitHub API или GitLab paid tier. MVP portability Linux runner; macOS REPL/package contract; Windows supported только после suite.

## 10. Optional Yabanin wrapper-first

```text
yb ci exec --session=on -- raider run --bundle pipelines.jar \
  --workflow audit --ci auto --profile yabanin --policy ci-readonly \
  --input "Проверь проект" --out artifacts/raider
```

Raider напрямую работает с OpenAI/Anthropic-compatible API без wrapper/gateway: [provider contract](provider-protocols.md). Если выбран Yabanin, использовать child-only `YABANIN_GATEWAY_URL`, `YABANIN_API_KEY`, `YABANIN_LABELS`, подтверждённые локальным Yabanin CI contract. При inherited session key Raider не mint второй root key; wrapper owns finish/accounting, Raider сохраняет local attempts/root labels. Самостоятельный mode Raider может own session mint/finish. Один session lifecycle owner на root, explicit в run.json.

Yabanin accounting report не заменяет Raider task verdict. `yb ci report --fail-over-cost` — post-hoc check, не hard admission. Подробнее и реальные gaps: [integration contract](yabanin-integration.md).

## 11. Обязательная CI-приёмка

1. Одинаковый bundle/input/backend fixture → одинаковый semantic result в generic/GitHub/GitLab contexts.
2. Closed stdin/noTTY/noANSI; никаких prompts или auto-allow.
3. Malformed context/bundle/input и policy deny не делают model call.
4. Matrix/retry attribution различима, secrets env не экспортируются.
5. SIGTERM parent cancels subtree/process groups; partial artifacts/code честны.
6. Disk-full/permission reporter failure никогда exit 0.
7. Fork fixtures не читают provider credentials, protected verifiers вне workspace.
8. XML/Markdown/output command/path injection fixtures безопасно сериализуются.
9. Все exits и precedence проходят table-driven tests.
10. Uncertain mutation не replay при restart/retry.
11. Headless packaged classpath/launch не содержит compiler/REPL/JLine и network dependency resolution.
12. Реальные template jobs проверены на чистом runner; YAML presence не считается acceptance.

M0 доказывает все контракты local/mock suite; actual hosted template readiness требует отдельного запуска владельцем/стенда и evidence. Обычные development gates не требуют paid provider.
