# ROADMAP-SDK: встраиваемый SDK в существующих фазах Raider

Date: 2026-10-12. Type: coordinator planning card.
Baseline: `4b46dc7`. Основание: запрос владельца изучить проект, уточнить
приоритеты, встроиться в текущий roadmap и закоммитить изменения плана.
Ответы владельца: Raider + SDK, первый сценарий CI; текущую волну не останавливать;
проект полностью open source. Состав команды, участники и сроки пока не заданы.

## Результат

Согласованный roadmap/backlog/entry prompt: embedding SDK получает scope и
наблюдаемую приёмку внутри P0/P4/P7/P10, пользовательские пилоты — P0/P7/P9.
Ближайшая ready карточка — RAI-115, audit границ SDK. Текущая волна продолжает
работу. Подробная пользовательская документация отложена по запросу владельца.

## Allowed paths и ownership

Coordinator единолично меняет `docs/roadmap-12k.md`, `docs/PLAN.md`,
`docs/prompts/opencode-12k-parallel.txt`, `docs/tasks/backlog.json`,
`docs/tasks/m1.md`, `docs/tasks/README.md`, эту карточку, `docs/work/board.md`
и итоговый `docs/work/records/ROADMAP-SDK.md`. Независимый spawn-reviewer
читает исходники и финальный diff; mutable workers/builds отсутствуют.
Одна плановая задача не создаёт восемь искусственных implementation lanes;
правила изоляции и параллельного исполнения продуктовых карточек сохраняются.

## Acceptance

- Normal: SDK-01–05 привязаны к существующим фазам, RAI-115 зарегистрирована
  синхронно в JSON/Markdown/index; указаны следующий ADR и dependencies.
- Error: план не объявляет SDK опубликованным, спрос доказанным или replay
  произвольных Scala effects реализованным; нет новых runtime/gates/public API.
- Concurrency: существующие card IDs/acceptance и активная волна сохранены;
  audit может идти независимо; contracts/build/board имеют единственного owner.
- Budget: верхнеуровневые фазы/бюджеты не увеличены; оценка добавочного труда и
  перераспределение остатка обязательны до implementation, старые cases не удалены.

## Проверки и non-goals

`python3 scripts/plan/validate.py`, `make quality-changed`, затем
`make quality-changed QUALITY_EXECUTE=1`, docs profile и независимый review.
Work record создаётся после выполнения; если runner включает records в product
fingerprint, после записи record повторить затронутые проверки. Не менять
gate policy ради исключения operational файлов.

Non-goals: реализация SDK/RAI-115, переписывание README/API guides, внешняя
публикация, сообщения потенциальным пользователям, платные вызовы, новый ADR
публичных signatures без отдельной карточки, изменения соседнего Yabanin.
