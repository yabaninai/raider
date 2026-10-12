# ADR-028: Plan mode — plan-фаза с read-only инструментами и явным approval

Status: **Accepted** (owner mandate 2026-10-12: «Пакет регистрации: RAI-107
Plan mode (ADR-028) — обязательно»). Дата: 2026-10-12. Owner: владелец
(мандат) + coordinator (дизайн в границах frozen-контрактов). Не меняет
ADR-024 (wire-схема событий frozen), gate policy и runtime-contracts;
сужает поверхность исполнения в выделенном режиме сессии.

## 1. Решение

AgentLoop получает **bounded execution mode «plan»**: в plan-фазе модели
доступны **только read-only инструменты**, фаза обязана завершиться
**структурированным план-предложением**; исполнение side-effect инструментов
и переход к выполнению плана **до явного approval владельца невозможны**.

## 2. Зафиксированные решения

| # | Решение |
| --- | --- |
| Enforcement сужения | На уровне **frozen `RecoveryClass`** (`ReadOnly`/`Idempotent`/`Mutating`, core/tool.scala): plan-фаза получает ToolRegistry только из `ReadOnly`-инструментов. Prompt-only запрет — не enforcement и не засчитывается |
| Вход в режим | Флаг запроса/сессии (request context / `:plan`-поверхность — отдельная wiring-карточка); по умолчанию режим выключен |
| Выход из фазы | Только **явное approval владельца**; auto-approve запрещён. Approval создаёт **отдельную execution-джобу** в обычном режиме с планом как контекстом |
| План-артефакт | Типизированный `PlanProposal` (результат plan-джобы), персистится рядом с артефактами рана; human-readable строки — через существующие Diagnostics-события. **Новое wire-событие не вводится** (ADR-024 frozen; расширение схемы = отдельная schema-card с independent review) |
| Malformed plan | Пустой/неструктурированный план → typed `PlanRejected`; bounded repair-attempts (инвариант 11: output repair — attempt, не бесконечен); артефакт при отказе не пишется |
| Cancellation | Plan-джоба отменяется как обычная (bounded cleanup, инварианты 4/5); cancel ≠ approval |
| Lineage | Plan-фаза и execution-джоба — раздельные записи (Phase-узлы ADR-027), план-джоба не «прячется» внутри execution |
| Бюджет | Plan-фаза живёт в общем root-бюджете (admission/usage не обходятся) |

## 3. Non-goals

Второй scheduler/runtime; auto-approve или таймер-based approval; изменения
ADR-024 wire-схемы и публичных core-схем молча; sandbox-гарантии поверх
tool policy (см. границы доверия AGENTS.md); UI-поверхность (`:plan`-команда,
TUI-экран плана) — отдельные wiring-карточки.

## 4. Приёмка (карточка RAI-107)

- PLAN-01: в plan-фазе вызов `Mutating`/`Idempotent` инструмента — typed
  policy-denial, loop жив; `ReadOnly` выполняются.
- PLAN-02: malformed план → typed `PlanRejected`, bounded repair, артефакт
  не пишется; валидный план → структурированный `PlanProposal` персистится.
- PLAN-03: без approval execution не стартует; approval → ровно одна
  execution-джоба; cancel plan-фазы — bounded cleanup, повторный план
  возможен.

Профили: static/unit/contracts/runtime/eval. Deterministic fixtures только —
реальные платные вызовы не implied (scripted backend).
