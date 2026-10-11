# ADR-026: модель-исправимые ошибки тулз чтения — структурированный feedback, не death-of-run

Status: **Accepted** (operator decision, 2026-10-10, autonomous self-dev campaign). Дата: 2026-10-10.
Owner: operator (campaign driver). Карточка: cross-cutting, вне RAI-нумерации;
не меняет `raider.core.tool` (RAI-002.b freeze) и не меняет семантику AgentLoop
emission (RAI-058, `AgentLoopEmissionSpec` остаётся эталоном).

## 1. Контекст

Автономная self-dev кампания (53 карточки, live-модель glm-5.3-flash через
гейтвей Yabanin) стабильно гибла на бытовых ошибках модели: `fs_read`
несуществующего пути, чтение каталога, бинарного файла. Классификация
`CodingToolset.toolOf` отправляла `ToolFailed` в `throw` → ZIO Defect →
`JobManager.settle` фиксировал «job died» и весь прогон умирал с exit 10.
Это делало цикл «модель ошиблась в пути → прогон потерян» нормой, что
противоречит назначению tool-набора (RAI-014: workspace tools для кодинг-агента).

## 2. Решение

1. `ReadTool`: `file not found`, `binary file`, «path is a directory» —
   переклассифицированы из `ToolFailed` в `InputValidation`. `InputValidation`
   уже входит в `isPolicyDenial` → `toolOf` возвращает модели структурированный
   `{"error":code,"detail":...}` и прогон продолжается (тот же канал, что и
   unknown_tool/invalid_args; см. комментарий POLICY в `toolOf`).
2. `RateLimitRetries`: `ProviderUnavailable` (502/503) ретраится наравне с 429 —
   в таксономии §14 он уже `retryable=true`; возникает до первого события стрима,
   поэтому дубликатов эмиссии нет.
3. Агентный цикл (AgentLoop), JobManager, контракты ошибок (§14) — НЕ менялись:
   `AgentLoopEmissionSpec` (RA-TOOLFAIL/RA-DEADLINE фатальность scripted-тулз)
   восстановлен бит-в-бит после эксперимента с catchAllCause, отклонённого как
   нарушение защищённой семантики.

## 3. Consequences

- Модель получает машинный код ошибки и исправляет аргументы; прогон выживает.
- Честность события сохранена: ToolFinished(Failed) для refusal-классов
  остаётся как был; error-JSON в success-канале виден в transcript.
- Спеки: ReadToolSpec не ассертит not-found/binary (проверено) — правок спек
  не потребовалось. Поведение задокументировано здесь как ADR-ссылка для
  будущих карточек RAI-014/016 rev.
- Реальные IO-сбои ( vanished mid-read, resolve IOException ) остаются
  crash-flavored — фальсификация успешного чтения по-прежнему невозможна.
