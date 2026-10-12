# Raider SDK: исследование Scala DSL для AI-native SDLC

Дата: 2026-10-12. Raider baseline: `3a60f93` (product inputs наследуют `4b46dc7`).
Это planning evidence, не реализация API, сравнительный benchmark или release.

## 1. Решения владельца и задача исследования

Владелец подтвердил в интерактивной сессии:

- Raider + SDK, полностью OSS, текущая wave продолжается, gateway — P11.
- Первый успех: написать Scala workflow и запускать его локально и в CI.
- Первый CI — локальный generic runner; система ещё не выбрана, не GitHub Actions.
  Forge тоже не выбран: сначала общий контракт и локальный Scala stand.
- Главное отличие: удобный DSL для AI-native software development life cycle.
- Первый проект: `../tokenrouter` (Yabanin), именно разработка его кода;
  использование его как LLM gateway остаётся отдельной поздней интеграцией.
- Целевой цикл включает создание PR, автоматические commit/merge, version/changelog,
  tag и сборку release-артефактов. Production deploy не входит; публикация
  пакетов/контейнеров не подразумевается сборкой артефактов.
- Release tag создаётся только локально, без push; публикация тега позже отдельно.
- Человек задаёт точки контроля в Scala workflow; один обязательный универсальный
  режим approvals не предписан.
- Preview может появиться до M4 с честной областью приёмки. Scala 3 + ZIO и
  текущий pinned toolchain; API между preview-релизами может меняться с миграцией.
- Crash resume не нужен первому SDK: interrupted/unknown и явный новый запуск.
- Политика подробных записей откладывается; preview для доверенных локальных данных.

Метод: чтение actual code и контрактов; независимые visible spawn audit/review;
официальные docs/repos конкурентов и платформ. Чужие библиотеки не запускались,
paid calls не выполнялись. Внешние источники проверены 2026-10-12; их current docs
не доказывают доступность каждой возможности в выпущенном артефакте.

## 2. Что предлагают ближайшие альтернативы

| Проект / primary source | Документированные поверхности | Следствие для Raider |
| --- | --- | --- |
| [LLM4S ZIO](https://llm4s.org/guide/zio), [agents](https://llm4s.org/guide/agents/) | ZIO wrappers/streams, cancellation, agents/tools и structured workflows | Наличие Scala/ZIO фасада уже не достаточное отличие |
| [LLM4S migration](https://llm4s.org/reference/migration.html) | Durable/graph runtime описан в текущей разработке; ряд изменений прямо помечен как ещё не выпущенный | Не смешивать development API и released capability при сравнении |
| [llm4zio](https://github.com/riccardomerolla/llm4zio) | ZIO embedding, scala-cli flows, plan/implement/review, Git/CI helpers, events и resumable планы | Самое близкое пересечение с SDLC DSL; проверять эргономику конкретного рабочего процесса |
| [LangChain4j agents](https://docs.langchain4j.dev/tutorials/agents/) | Experimental agentic module, workflow patterns, AgenticScopeStore, checkpoints и recovery позиции planner | Workflow recovery существует у альтернатив; tree inspection не исчерпывает их возможности |
| [Koog](https://docs.koog.ai/), [persistence](https://docs.koog.ai/features/agent-persistence/) | Kotlin стратегии/tools, structured output, checkpoints и восстановление | JVM/typed workflows и persistence сами по себе не уникальны |
| [LangGraph persistence](https://docs.langchain.com/oss/python/langgraph/persistence), [interrupts](https://docs.langchain.com/oss/python/langgraph/interrupts) | Checkpointers, stores и ожидание человека; возобновление node повторяет код до interrupt | Объяснять явно разницу между checkpoints, replay и повтором side effects |

Матрица отражает документацию, не ранжирование качества. Отсутствие упоминания
функции здесь не означает её отсутствие. В частности, у LangChain4j есть
документированное workflow recovery; гарантии deterministic replay/ exactly-once
внешних мутаций из этого не следуют. Версии зависимостей конкурентов не закреплены
для запуска; сравнение исполнения потребует отдельного pinned protocol.

Рабочая гипотеза позиционирования: **Raider позволяет выразить собственный
AI-native SDLC обычной Scala, отладить тот же workflow локально и исполнить его
в CI с проверяемыми переходами между планом, кодом, checks, review и интеграцией.**
Runtime ownership/budgets/cancellation поддерживают это обещание. Его ценность
проверяется на TokenRouter; преимущество перед альтернативами пока не доказано.

## 3. Технические выводы, влияющие на порядок работ

Полный source inventory и packet для ADR — результат RAI-115,
`docs/research/sdk-embedding-audit.md`. Ключевые точки baseline:

| Факт | Источник | Решение для плана |
| --- | --- | --- |
| `Task[A]` содержит `ZIO[Any, RaiderError, A]`, `Program[I,O]` типизирован | `modules/core/.../task.scala`, `program.scala` | Не обещать generic ZIO environment/error type; выбрать способ передачи host services в ADR |
| Удобный facade блокирует через `Runtime.default` и содержит registries | `modules/repl/.../facade/FacadeOps.scala`, `ReplSession.scala` | Не экспортировать REPL целиком как SDK; lifecycle/config/launch boundary выделять по потребности |
| Bundle v1 принимает `Program[String,String]` | `modules/core/.../bundle.scala`, `modules/cli/.../run/BundleLoader.scala` | Preview честно обозначает строковую внешнюю границу; typed internals не равны typed wire; codecs/version change — отдельный contract scope |
| Bundle path допускается к модели снаружи; native loop делает admission внутри | `modules/cli/.../run/HeadlessRunner.scala` (`execute`, admission note) | Shared workflow требует проверки единого root/ledger без double admission, особенно maxLLM=1 |
| `AgentLoop` по умолчанию получает Unknown estimate и settle в текущем пути — `MicroUsd(0)` | `modules/runtime/.../loop/AgentLoop.scala` (`attemptEstimate`, `runRound`) | Наличие ledger не доказывает provider monetary accounting; отдельно проверять заявленные caps/unknown/refunds |
| Сборка всего проекта pinned Scala 3.9.0 | `build.sbt` | Первый preview остаётся на текущем toolchain по решению владельца; не заявлять поддержку старых Scala 3 |
| Lineage Scala projection и replay имеют разные реализации | `modules/dsl/.../LineageProjection.scala`, `modules/cli/.../lineage/*` | Компиляция графа не заменяет запуск нового редактируемого workflow |

Scala TASTy backward compatibility означает, что библиотека от более нового minor
compiler не становится автоматически доступна более старому compiler пользователя.
Это ограничение первой матрицы поддержки, а не основание срочно менять REPL compiler.
[Scala compatibility](https://docs.scala-lang.org/scala3/reference/language-versions/binary-compatibility.html).

Maven artifact требует не только JAR: нужны разрешаемые зависимости, metadata,
sources/docs и signing для выбранного public registry. Проверка через изолированный
локальный repository предшествует public publish; namespace/credentials/владелец
release пока не определены. [Central requirements](https://central.sonatype.org/publish/requirements/).

## 4. Целевой опыт автора workflow

Сначала автор описывает один сценарий обычными `Program`, `andThen`, `all`,
`batch` и Scala functions; helpers SDLC появляются только вокруг повторяющихся
предметных шагов. Новый parser/macros или второй graph scheduler не нужны.

Предлагаемая структура процесса (не реализованная API-сигнатура):

```text
task + repo baseline
  → requirements / plan
  → configured human checkpoint (если включён)
  → implementation in isolated workspace
  → trusted checks → bounded repair
  → independent review → bounded rework
  → commit + PR → integration checks on candidate
  → configured merge policy → merge
  → version / changelog / tag / release artifacts
```

Каждый переход получает фактический вход и типизированный результат, включая
failure/needs-approval/uncertain. Review не подменяет детерминированные checks.
Repair/review loops имеют попытки/deadline и общий root budget. Commit/merge/release
допускаются по явной policy, ссылающейся на проверенный candidate, а не по тексту
«готово» от модели. Это требования к будущим ADR и acceptance, не новый DSL surface.

Критерии эргономики: автор может заменить один этап, добавить свой tool, настроить
checkpoint, ограничить repair loop, посмотреть промежуточный результат и выполнить
тот же файл локально/в CI без копии оркестрации. Метрика — самостоятельное выполнение
этих действий и понятность ошибок; количество строк кода само по себе не gate.

## 5. Автоматическая интеграция и release

Автоматические commit/merge — подтверждённая цель. Её приёмка должна проверять:

- Связь task/run, исходного revision, candidate tree, checks/review и commit SHA.
- Один integration owner; сдвиг target/head, конфликт и изменённый candidate требуют
  соответствующей новой проверки; старый pass не прикладывается к другому дереву.
- PR/status/merge adapter учитывает реальные права и branch rules. GitHub допускает
  указание ожидаемого head SHA при merge, но оно само по себе не закрепляет base;
  integrated candidate/merge queue или эквивалентная проверка нужны отдельно.
  [GitHub merge API](https://docs.github.com/en/rest/pulls/pulls),
  [GitLab merge API](https://docs.gitlab.com/api/merge_requests/).
- Timeout после внешней мутации — сначала read-only reconciliation наблюдаемого
  состояния. Не выполнять слепой повтор commit/PR/merge/tag и не обещать exactly-once.
- Version/changelog/tag и release artifact связаны с принятым commit и проверенным
  revision. Конфликт версии/tag, сборка с ошибкой и отмена имеют явный исход;
  новый запуск не перетирает существующий release. Publishing/deploy не входят.

Git/forge/release steps — предметные tools/adapters над тем же runtime; внешний
CI orchestrator продолжает управлять jobs, approvals, secrets и публикацией artifacts.
Не создавать второй CI scheduler. Для первых acceptance — disposable local Git repos
и локальный Scala HTTP stand forge; реальный TokenRouter изменяется только отдельной
его карточкой и с соблюдением его правил.

Конфигурация workflow/tool policy не является sandbox для Scala или запускаемого
кода репозитория. Secret-bearing job не должен выполнять недоверенный fork-код;
права интеграции и запуск проверок разделяются средствами CI environment.
Это конкретная граница CI-сценария, не обещание полной изоляции внутри SDK.
[GitHub trust guidance](https://docs.github.com/en/actions/reference/security/securely-using-pull_request_target).

## 6. Human checkpoints без durable resume

Настройка checkpoint принадлежит автору Scala workflow. В локальном процессе
можно ждать ввода с deadline/cancel. Headless путь должен выдать versioned
needs-approval artifact и понятный exit либо получить заранее разрешённое решение
из trusted invocation context. Долгое ожидание человека не требует живой JVM.

После завершения процесса это **новая invocation**, проверяющая task/base/candidate
и входные artifacts, с новым root ID и связью с предыдущим run; продолжение
произвольной Scala fiber не обещается. Read-only подготовка может повторяться;
перед мутациями сверяются postconditions и known/unknown outcome. Точная форма
approval artifacts и invalidation — предмет ADR, не уже существующий контракт.

AWS AI-DLC описывает работу по этапам и human validation как методологию. Мы
используем это как материал для этапов, а не переносим обязательные approvals
AWS поверх выбранной владельцем настраиваемой модели.
[AWS AI-DLC](https://aws.amazon.com/blogs/devops/ai-driven-development-life-cycle/),
[adaptive workflows](https://aws.amazon.com/blogs/devops/open-sourcing-adaptive-workflows-for-ai-driven-development-life-cycle-ai-dlc/).

## 7. Поставка, измерения и ограничения оценки

Первая поставка — experimental **workflow-authoring preview**: source → local run
→ compiled bundle → headless CI на одном native runtime. Чистый host consumer
SDK-01/02 остаётся отдельным P4 результатом; plugin UI/M2 и полная M4 не блокируют
обратную связь по принятому узкому preview. Preview не закрывает milestone.

Полный SDLC добавляется последовательными accepted slices: план/код/checks/review,
затем commit/PR/merge, затем release artifacts. Конечная цель владельца сохраняется;
урезанный preview не объявляется полным циклом. Детальные руководства остаются
поздней работой, но пример и одна воспроизводимая команда обязательны сразу.

У первой проверки уже есть конкретный участник/проект: владелец и TokenRouter.
Замерять setup failures, помощь автора Raider, время до полезного изменения,
вердикт trusted checks/review, попытки ремонта, известную/неизвестную стоимость,
время ожидания человека, merge/release outcome и повторное применение. Внешние
OSS-пользователи проверяются отдельно; dogfooding не подтверждает внешний спрос.

До увеличения поверхности SDK полезен такой stop/review trigger: после одного
прохода onboarding никто не выполняет целевую задачу самостоятельно, либо успешный
первый запуск не приводит к новой задаче — исследовать причины до расширения API.
Это продуктовый decision point, не способ объявить engineering tests зелёными.

13 100 h остаются историческим envelope. Более полный SDLC с forge/release
добавляет работу к первоначальному «patch + checks», его нельзя считать бесплатно
поглощённым P7/P10. Пакеты и estimates RAI-115 — исходная оценка SDK; новый SDLC
contract packet отдельно оценит интеграцию/release, fixtures/review и сопровождение.
Accepted plugin/runtime slices исключаются из оценки остатка. Команда, фактические
used/reserved hours и календарь пока unknown; новые суммы не утверждены.

## 8. Следующие решения и карточки

1. RAI-115 source audit принят после независимого review (`c156f40`, merge `e2deeb3`).
2. RAI-116: отдельный ADR workflow-authoring/launch boundary (P0.B), независимый
   от ранее принятого UI seam. Включить string bundle/typed internals, host scope,
   один admission/budget, error/cancel, consumer classpath и preview compatibility.
3. RAI-117: отдельный SDLC contract/acceptance packet для TokenRouter: stages,
   configurable checkpoints, checks/review, commit/PR/merge и release artifacts;
   platform/repository rules, effects reconciliation и отдельная estimate.
4. До acceptance RAI-117 coordinator регистрирует case→phase→implementation/verifier
   card IDs с owners, dependencies, exact allowed paths и estimates; implementation
   режется в пакеты 8–24 h. M3 требует WF-01/02 + SDLC-01–04 на local stand,
   M4 — также SDLC-05; real forge PR/merge pending до выбранного adapter и его
   acceptance. Build/API/gates имеют единственного owner; protected verifier changes
   получают отдельную карточку и review. Текущая wave продолжается.

Уточнения, которые нельзя выдумать: выбранный forge/CI engine, актуальные удалённые
branch rules, release namespace/maintainer, доступный engineering budget,
поддерживаемая схема headless approval artifact. Невыбранные CI/forge не блокируют
контракт и локальный stand, но не допускают claims о реальных PR/merge.

## 9. Read-only discovery TokenRouter как SDLC consumer

Путь: `../tokenrouter`. HEAD `91e6ce0fd293f69bdea9f9a8b9876f32da35451f`, branch
`master`; checkout dirty до исследования. Ни файлы, ни ветки не изменялись,
build/tests не запускались. Это discovery разработки кода, не gateway API/P11.
Исследованы текущие рабочие версии следующих файлов; только два из этих семи
изменены относительно HEAD (прочие dirty файлы проекта также сохранялись):

| Input | SHA-256 working bytes | Dirty |
| --- | --- | --- |
| `AGENTS.md` | `44a7366a481373ce22f30d123e7b66271ce39fe5def21167ceb903eff5f8ff18` | yes |
| `Makefile` | `a93bb42cd6044ee4f5e103c77c11f585c006f6cea819d9a94e6b27f378d0a7b7` | yes |
| `CONTRIBUTING.md` | `13229874ceb09a0d6cc1324ecf73527c3b5007f495c988f4ce2be957b9dfe78f` | no |
| `go.mod` | `255281ad1036687834921de8608bf0f137634b76970fba0c2377fc776f016e56` | no |
| `.github/workflows/ci.yml` | `d7261ab80698ff0314f6d3659705b327c149adab926461ec4931a145be767f02` | no |
| `.github/workflows/release.yml` | `c736428be63fece3171b79251bfeea31ca449bab6dff912899df2afbc3e96d2e` | no |
| `scripts/release/release-artifacts.sh` | `80be562a18d6d35a65097c18d79e6c1ee34751c01ae5c16ede052643d0bbb967` | no |

Наблюдения для будущего adapter, не команды, выполненные в этом аудите:

- Проект преимущественно Go, есть Python/Node SDKs и Playwright; DSL Raider
  остаётся Scala/ZIO. Доработка TokenRouter сохраняет его собственные language rules.
- `AGENTS.md:12` и `Makefile:253,420`: сначала `make quality-changed`, затем
  `make quality-changed QUALITY_EXECUTE=1`; для committed work исходный task base
  передаётся как `QUALITY_BASE=<base>`. Evidence — `artifacts/quality/<run>/manifest.json`,
  проверяется `python3 scripts/quality/quality.py evidence <manifest> --require changed`
  (и `--base <base>` для committed work). Это отличается от Raider runner API.
- `CONTRIBUTING.md:5` требует lint/test и review dependency/security изменений.
  DCO-процедура помечена DRAFT, при этом owner decision о достаточности sign-off
  записан отдельно; integration contract должен уточнить актуальные требования
  к identity/signoff, не выдумывать согласие от имени contributor.
- `.github/workflows/ci.yml` содержит Go/race/lint/security, SDK/docs/Helm и integration
  jobs. Их наличие не выбирает GitHub Actions для Raider: владелец выбрал local CI.
- `Makefile:68` / `release-artifacts.sh:15`: четыре binaries, SHA256SUMS, SPDX через
  Syft либо go build manifests, optional signing, checksum/build-info smoke.
  VERSION берётся из `git describe --tags --always --dirty`, commit из HEAD.
  Команда чистит часть выходного каталога; использовать отдельный `RELEASE_DIR`
  для candidate. Сама она не создаёт version/changelog/tag.
- `.github/workflows/release.yml:1,18,134`: pushed `v*` публикует GHCR images и
  запускает staging deploy. Владелец после этого finding выбрал **local tag only**.
- В текущих target rules есть запреты push/publication; будущие approved PR/merge
  capabilities потребуют отдельной TokenRouter карточки с точным scope. Нельзя
  считать разработку Raider разрешением изменить эти файлы сейчас.
- Remote rules/required checks не проверены: `gh` не аутентифицирован. Это
  unavailable discovery, не отрицательный вывод об отсутствии branch protection.
  Наличие GitHub origin не выбирает первый forge; его adapter откладывается.

Эта карта требований позволяет проектировать generic task/check/artifact protocol
сейчас. Реальный target run потребует новой immutable рабочей копии, свежего чтения
repo rules и actual gates; нельзя брать текущий dirty checkout как чистый fixture.
