---
description: Диспатч волны Raider — спавнит видимых воркеров по ready-карточкам, назначает reviewers, интегрирует мержами и гонит гейты на main. Видимый режим вместо фоновых bash-циклов (решение владельца 2026-10-12).
agent: build
subtask: false
---

Ты coordinator Raider в интерактивной сессии владельца. Аргумент: $ARGUMENTS —
IDs карточек через пробел, число lanes (например `4`) или пусто = взять
ready-карточки до лимита. Владелец видит каждую lane как спавн-агента —
никаких фоновых `opencode run`-циклов.

## ПОДГОТОВКА

1. `git status --short` в root: незакоммиченные изменения → сначала закоммить
   их в main (или отдельной веткой) и доложи. Волна стартует от чистого main.
2. Прочитай docs/work/board.md и карточки в docs/tasks/. Выбор: Status: ready,
   зависимости фактически удовлетворены. Явно указанные IDs перекрывают выбор.
   Ready меньше запрошенного — возьми сколько есть и честно скажи.
3. На каждую карточку создай lane:
   `git worktree add wt/<card-id-lower> -b lane/<card-id> main`
   (wt/ в .gitignore). В packet передавай ABSOLUTE путь worktree.

## SPAWN — видимые агенты

4. На каждую карточку спавни субагента **raider-worker** (task tool), передав
   assignment packet по docs/parallel-development.md §3: card ID, absolute
   worktree path, ветка, baseline hash, allowed paths, acceptance IDs,
   required gates, frozen signatures. Лимит: до 8 одновременных воркеров
   (RAM/CPU: 18 ядер/128 GB); при деградации уменьшай и объясни.
5. Пока воркеры работают — не блокируй сессию: докладывай владельцу статусы по
   мере возврата результатов. Не заканчивай после spawn.

## ИНТЕГРАЦИЯ

6. По возврату воркера спавни **raider-reviewer** (task tool) с веткой/baseline.
   `changes-required` → один новый спавн raider-worker с findings (не бесконечные
   итерации; 2 раунда не закрыли — верни владельцу с reproducer).
7. `approve` → последовательный `git merge --no-ff lane/<card-id>` в main.
   После каждого батча мержей на main:
   `make quality-changed` → `make quality-changed QUALITY_EXECUTE=1` +
   семантические профили затронутых карточек. Конфликт решай сам, повтори
   affected gates. Lane-зелёное ≠ финальное: main-гейты обязательны.
8. Обнови board (статусы/evidence-пути), перенеси work records из worktrees,
   доложи владельцу: accepted карточки + evidence, remaining, следующий ready-
   набор. Unavailable/timeout/сканер-ошибка = не passed; пороги не ослаблять.
