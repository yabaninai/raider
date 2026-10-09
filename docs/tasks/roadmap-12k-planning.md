# ROADMAP-12K: план удобного standalone Raider и prompt для OpenCode

Status: partial (deliverables prepared; repository validation blocked).
Date: 2026-10-05. Type: documentation planning card.
Dependencies: текущий код, требования владельца, working-product и parallel runbook.

## Результат

Один согласованный пакет: 12 000 инженерных часов, ранние вкладки/статусы,
удобные плагины, исполнимый порядок независимых lanes, Yabanin строго последним,
готовый prompt для OpenCode. Этот пакет не реализует продуктовые функции.

## Allowed paths

- `docs/roadmap-12k.md`, `docs/prompts/opencode-12k-parallel.txt`.
- `docs/roadmap-10k.md`, `docs/PLAN.md`, `docs/handoff-opencode.md`,
  `docs/prompts/README.md`, `docs/prompts/MASTER-10K-STATE.md`,
  `docs/prompts/MASTER-10K-PHASE0.md`: ссылки и предупреждения о новой очередности.
- Эта карточка, `docs/work/board.md`, `docs/work/records/ROADMAP-12K.md`.

## Acceptance

- Normal: фазы и пакеты дают ровно 12 000 h; ранние milestones проверяемы;
  prompt ссылается на канонический план и задаёт coordinator + до 3 workers.
- Error: старые prompts не направляют исполнителя к раннему Yabanin;
  существующий код не объявляется отсутствующим, план не объявляется реализацией.
- Concurrency: общий root/build/contracts/board имеют одного owner;
  изоляция writers и review обязательны; зависимости и очередность интеграции явные.

## Проверки и границы

Plan validator; `make quality-changed`, затем execute с сохранённым baseline
inventory; независимое чтение плана. Runtime/registry/public API не меняются.
Незавершённые требования W01–W18 сохраняются; staged acceptance явно отделена.
Пакеты нового roadmap — планирование; implementation child cards регистрируются
coordinator в существующем backlog перед назначением. Никаких новых green gates
в этой карточке. Пользовательский untracked research-файл не редактируется.
