<!-- Translated from Russian original. Key terms preserved as-is. -->

# Raider: подробная программа разработки

Исходный план: 2026-10-02; execution revision: 2026-10-03. Есть feasibility spike, продуктовый runtime ещё не реализован. Actual status сверять по code и board.

Итоговый scope: CI-primary; удобный реальный REPL; оба direct API protocols; optional Yabanin; 53 Raider cards и 3 optional companion cards. Текущий entrypoint: `opencode` → `/raider-finish` (`/raider-build` alias), [инструкция и prompt](handoff-opencode.md).

## Уточнение после аудита 2026-10-03

[Рабочий продукт W01–W18](working-product.md) определяет finish target; [parallel runbook](parallel-development.md) — разрешённые parallel lanes; [review](reviews/2026-10-03-readiness.md) — факты о сделанном; [stage-aware gates](quality-gates.md) устраняют циклы ранней приёмки. Эти документы уточняют исходную очередность и правила исполнения ниже. Старые номера карточек сохранены, M0 — промежуточный результат, sessions/context/SPI/package обязательны для useful coding agent.

## Цель

Создать универсальный расширяемый Scala harness для исполнения агентных сценариев внутри CI job в GitHub/GitLab/других pipelines. Сценарий должен принимать versioned inputs, запускать синхронных/асинхронных сабагентов, соблюдать policy/budgets/deadlines, выдавать проверяемый результат, exit code и artifacts. Полноценный REPL нужен для локального создания и отладки того же сценария без отдельного execution model.

Ключевые свойства:

1. Portable non-TTY CI runner с одинаковой семантикой в разных orchestrators.
2. Настоящая Scala в REPL: `val`, `def`, `case class`, imports, многострочный ввод и типы.
3. Однострочный foreground/background запуск с явными эффектами.
4. Управляемые дети: владелец, дедлайн, лимиты, сообщения, отмена, результаты.
5. Расширение инструментов/профилей/backend отдельными модулями.
6. Возможность использовать профили pi/OpenCode и запускать их движки через адаптеры.
7. Самостоятельные OpenAI-compatible/Anthropic-compatible API и optional Yabanin gateway/accounting.
8. Качество обеспечивается воспроизводимыми проверками и маленькими карточками, а не уверенностью модели.

## Как читать пакет

| Документ | Назначение |
| --- | --- |
| [CI runtime](ci-runtime.md) | Основной product contract: invocation, trust, exits, artifacts, orchestrator adapters |
| [Provider protocols](provider-protocols.md) | Прямые OpenAI/Anthropic wires, optional gateway, capabilities/usage |
| [Удобство DSL](dsl-experience.md) | Быстрый пользовательский путь и единообразные verbs |
| [Product и REPL DSL](product-repl.md) | Полный контракт, точные semantics, команды |
| [Runtime и контракты](runtime-contracts.md) | Модули, состояния, ownership, concurrency, tools, plugins |
| [Интеграция с Yabanin](yabanin-integration.md) | Проверенные точки интеграции, ограничения, staged rollout |
| [Этапы и зависимости](development-plan.md) | Очерёдность, milestone acceptance, scope |
| [Карточки реализации](tasks/README.md) | Маленькие задания для моделей |
| [Quality gates](quality-gates.md) | Проверки, evidence, матрица рисков, release policy |
| [Работа простыми моделями](model-workflow.md) | Контекст, handoff, независимый review, escalation |
| [Запуск в OpenCode](handoff-opencode.md) | Project command, prompt и resume procedure |
| [Задание для z.ai](handoff-zai.md) | Готовый стартовый prompt и правила исполнения |
| [Источники и ADR](sources-decisions.md) | Проверенные источники, принятые/отложенные решения |
| [Snapshot Yabanin](research/yabanin-snapshot.json) | Commit, hashes и dirty markers изученных локальных файлов |

## Проверка самого пакета

```sh
python3 scripts/plan/validate.py --out artifacts/plan-review/report.json
```

Реализованный validator проверяет links/JSON/fences/card metadata/dependency DAG и соответствие OpenCode command каноническому prompt. Optional `--yabanin-repo /path/to/checkout` сверяет source snapshot hashes read-only. Это проверка проектного пакета; Scala examples, runtime/PTY/CI gates пока не реализованы и не объявляются passed.

## Условия исполнения

- Все примеры DSL и commands в пакете — целевые контракты. Их работоспособность надо доказать карточками и compiler/PTY gates.
- Сначала RAI-001: проверка toolchain, headless execution и REPL embedding. Она может уточнить технический способ исполнения, но не упрощать REPL до парсера пары команд.
- Bootstrap: RAI-001 → RAI-003 build → RAI-002 compile/schema contract → RAI-004 gates; затем vertical slice.
- Нет автоматического push/deploy/merge: этот пакет описывает разработку и локальную приёмку.
- Нативный минимальный agent loop нужен в MVP. Pi/OpenCode bridges добавляются следующим этапом; они не должны быть единственной возможностью запустить Raider.
- Язык runtime — Scala. Python допустим для маленького переносимого quality runner/проверок metadata; он не становится агентным runtime.
- Языковая политика (владелец, 2026-10-03): вся дальнейшая разработка — Scala 3 + ZIO. `modules/**`, `contracts/**`, `examples/**`, продуктовые tests и test-стенды — только Scala. Python ограничен: `scripts/plan/validate.py`, `scripts/quality/**` (runner RAI-004), glue `scripts/bootstrap.sh` и замороженные исторические `experiments/**`; Python не попадает в поставку и не растёт. Тестовые HTTP-стенды провайдеров пишутся на Scala (JDK built-in HttpServer) внутри модулей; допускается локально запущенный OpenAI-compatible стенд для тестов по явной конфигурации; платные удалённые endpoint'ы — только с owner cap.
- Реальное подключение Yabanin проверяется на отдельном стенде. Чтение его исходников не доказывает готовность deployment.

## Первая CI-приёмка

```text
raider run --bundle pipelines.jar --workflow inspect --ci generic \
  --context ci-context.json --input "проверь проект" --mock --out artifacts/raider
```

Одинаковый compiled workflow выполняется без TTY, завершает owned children, сохраняет run/result/events/JUnit/summary и возвращает code по policy. Прогон повторяется с GitHub/GitLab environment fixtures, timeout, SIGTERM и malformed input. Compiler REPL не нужен в classpath runner.

## Локальная REPL-приёмка

```text
$ raider repl --mock
raider> scout.ask("Найди README")
raider> val a = scout.start("Найди интерфейсы")
raider> val b = scout.start("Найди тесты")
raider> :jobs
raider> a.await
raider> b.cancel()
raider> (scout andThen reviewer).ask("Оцени проект")
raider> :load workflows.raider.scala
raider> :run inspect "проверь проект"
raider> :exit
```

В результате нет orphan jobs/processes, корректно отображены ошибки, compiler state сохранился после неудачного ввода, фактические network/tool effects происходят только после запуска. Этот transcript должен стать исполняемым acceptance fixture.
