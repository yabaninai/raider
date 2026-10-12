# REPL и typed bundle: проверяемые примеры

Это руководство показывает только поведение текущего REPL и bundle loader. Все
REPL-примеры используют scripted fixture backend (`RAIDER_PROVIDER=mock`):
ответ содержит `[scripted-fixture]`, сеть и модель не вызываются. Эти примеры
проверяют Scala compiler, API и runtime-механику; они не доказывают качество
ответов модели.

## Подготовка

Из корня репозитория выполните:

```sh
sh scripts/build/user-examples.sh
RAIDER_CP=$(cat artifacts/user-examples/classpath.txt)
```

Скрипт собирает classpath и bundle в `artifacts/user-examples/`. Перед запуском
REPL-пункта убедитесь, что `RAIDER_CP` содержит одну строку classpath. Каждый
файл ниже запускается в отдельном процессе, поэтому binding-и не переносятся
между примерами.

## 1. Настоящий Scala compiler

Ввод `val`, `case class`, типизированная функция и многострочное определение
выполняются встроенным Scala 3.9.0 REPL. `assert` проверяет результат до
печати маркера.

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main \
  < examples/user-guide/repl/01compiler.repl
```

Ожидаемый маркер: `EXAMPLE-01 PASS`.

## 2. Ленивый запрос и map

`scout(prompt)` создаёт lazy descriptor. Вызов `run()` выполняет его; `map`
преобразует текст scripted fixture в длину. Проверяется только то, что fixture
ответ непустой.

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main \
  < examples/user-guide/repl/02lazy-map.repl
```

Ожидаемый маркер: `EXAMPLE-02 PASS`; длина зависит от scripted fixture текста.

## 3. Parallel `all` и упорядоченный `batch`

`all` над двумя lazy asks запускает их через tuple-композицию. `batch` допускает
ограничить параллелизм и сохраняет порядок входа; пример проверяет порядок,
прикрепляя метку к результату каждого descriptor. Оба backend-ответа остаются
одинаковым фиксированным fixture текстом и ничего не говорят о качестве модели.

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main \
  < examples/user-guide/repl/03parallel-batch.repl
```

Ожидаемый маркер: `EXAMPLE-03 PASS`.

## 4. Фоновая job

`start` возвращает handle фоновой job; `await` ждёт её завершения. После
проверяемого ответа команда `:jobs` показывает snapshot завершённой job; её ID
назначается процессом, поэтому конкретное значение заранее не фиксировано.

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main \
  < examples/user-guide/repl/04background-job.repl
```

Ожидайте `EXAMPLE-04 PASS`, затем строку `j_<id>  Succeeded  worker: ...` от
`:jobs`. В этой scripted-сессии проверяется job lifecycle, а не работа агента.

## 5. Session и история turns

`openSession(worker)` хранит user/assistant turns в памяти объекта `Chat`.
Файл проверяет два вызова `ask` и четыре сообщения в истории. У текущего `Chat`
нет метода `close`; история не сохраняется на диск и исчезает при завершении
процесса. Scripted ответы фиксированы и не доказывают, что модель использовала
контекст предыдущего turn.

```sh
RAIDER_PROVIDER=mock java -cp "$RAIDER_CP" raider.repl.Main \
  < examples/user-guide/repl/05session.repl
```

Ожидаемый маркер: `EXAMPLE-05 PASS`.

## 6. Typed workflow в bundle

`TaskSummary` реализует `BundleWorkflow`. Внутри он связывает два типизированных
`Program`: `String => List[String]` и `List[String] => String`. Это обычный
детерминированный Scala-код через `Program.andThen`; provider и сеть не нужны.
Bundle manifest объявляет `schemaVersion: 1`, entry `task-summary` и
`trusted-local`. Объявление trust описывает заявленный уровень доверия, но само
по себе не является sandbox: исполняйте только доверенный bundle.

Скрипт подготовки компилирует класс и создаёт
`artifacts/user-examples/task-summary.jar`. Передайте входную строку CLI:

```sh
java -cp "$RAIDER_CP" raider.cli.Main run \
  --bundle artifacts/user-examples/task-summary.jar \
  --workflow task-summary \
  --input 'fix bug, add test' \
  --out artifacts/user-examples/summary-run \
  --mock
```

Результат workflow: `2 tasks: fix bug | add test`. Он находится в артефактах
созданного каталога `artifacts/user-examples/summary-run/work-<id>/`; точный
каталог выводится CLI. Этот пример использует `--mock` только для интерфейса
headless runner: сам workflow не обращается к model backend.

Пустая строка должна завершиться ошибкой валидации workflow (`InputValidation`,
CLI exit code `21`), а не успешным пустым summary. Не используйте этот
негативный вызов в shell с `set -e` без обработки ожидаемого exit code.
