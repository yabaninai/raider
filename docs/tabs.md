<!-- RAI-072: документация ФАКТИЧЕСКИ реализованного поведения вкладок сессии
     (модуль ui, карточки RAI-056/057/061/088, LIN-04A). Новых решений не
     вносит; каждое утверждение снабжено ссылкой file:line в этом дереве. -->

# Вкладки сессии: SessionRegistry и таб-шелл

Слой вкладок — это UI-модуль `modules/ui/src/main/scala/raider/ui/`: read-model
реестр открытых вкладок над швом событий runtime→UI (см. [events.md](events.md))
и тонкий JLine-шелл над ним. Документ описывает только реализованное поведение.

## 1. Статус реализации (честно)

- Реестр и шелл реализованы и покрыты unit-тестами
  (`modules/ui/src/test/scala/raider/ui/`: `SessionRegistrySpec`,
  `TabShellSpec`, `AuxTabSpec`, `ShellViewsStateSpec`) и PTY-пробником
  `scripts/quality/tabs_pty_probe.py`.
- `TabShell` в этом дереве НЕ подключён к продакшн-точке входа REPL: вызовы
  `TabShell.run` живут только в тестах (`TabShellSpec.scala:121`,
  `TabShellPtyApp.scala:68` в `modules/ui/src/test/scala/raider/ui/`);
  потребители `raider.ui` в `modules/*/src/main` вне самого модуля отсутствуют.
- `:new` создаёт вкладку с СИНТЕТИЧЕСКИМИ id job/root
  (`ShellExecutor.scala:61-66` в
  `modules/ui/src/main/scala/raider/ui/`); реальная привязка вкладки к runtime-job
  (карточка RAI-062) и REPL cancel-binding не реализованы.

## 2. Реестр SessionRegistry

`SessionRegistry` — UI-состояние вкладок над `EventHub`
(`SessionRegistry.scala:116-134` в
`modules/ui/src/main/scala/raider/ui/SessionRegistry.scala`). Ключевые
свойства:

- Идентичность вкладки: `TabId` присваивается один раз при `create` и не
  меняется (`SessionRegistry.scala:18-24`); вкладки не мутируют — наблюдаемое
  состояние живёт в ref'ах реестра (`SessionRegistry.scala:240-254`).
- `create(jobId, rootId, label)`: сначала подписка на hub без окна потери,
  затем создание состояния и запуск consumer-волокна; новая вкладка становится
  focus'ом (`SessionRegistry.scala:137-140,289-337`).
- Один hub-конверт доставляется per-tab consumer'ом: дедупликация и
  пересортировка по `seq` до свёртки (относительный порядок классов доставки
  hub не специфицирует) — `Reorder` (`SessionRegistry.scala:256-282`);
  свёртка через `SessionReducer.reduce` (`SessionRegistry.scala:445-463`).
- Чтение модели: `tabs` (порядок создания), `tab(tabId)`, `focused`,
  `snapshot`, `history`, `scrollback` (`SessionRegistry.scala:142-208,339-424`).
  Статус в `TabView` берётся из свёртки hub (`EventHub.snapshot`) —
  авторитетное состояние job; `lastSeq` — локальный watermark вкладки
  (`SessionRegistry.scala:63-79,613-627`).
- Unread: каждое событие не-фокусной вкладки увеличивает счётчик, фокус
  сбрасывает его в 0 (`SessionRegistry.scala:521-528`); фокус и навигация —
  чисто UI-операции: ни публикации, ни отмены, ни вызовов job/hub/policy
  (`SessionRegistry.scala:125-128,379-394`).
- Draft — UI-локальная строка вкладки (не событие, не публикуется):
  `draftAppend`/`draftClear`/`draftGet` (`SessionRegistry.scala:190-197`).
- Ограниченные буферы с честными счётчиками вытеснения: история последних 200
  свёрток (`HistoryCap`, `SessionRegistry.scala:212-213,465-478`), scrollback
  последних 500 строк Diagnostics/ToolProgress
  (`ScrollbackCap`, `SessionRegistry.scala:215-216,480-509`); Terminal-снимок
  всегда остаётся последним элементом истории и в `snapshot`
  (`SessionRegistry.scala:44-48`).
- Разбиение текста в scrollback: CRLF → LF, split по LF, завершающий перенос —
  терминатор, а не пустая строка (`SessionRegistry.scala:511-519`).
- Ошибки — типизированные: `UnknownTab` и `NoTabs`, молчаливых unknown'ов нет
  (`SessionRegistry.scala:27-38`).
- `close(tabId)`: статус job берётся из hub-снимка (авторитет; `None` = job ещё
  ничего не публиковал — считается живым); терминальный — удаляется
  (`Closed`), живой — реестр ничего не меняет и возвращает `RunningChoice`
  (`SessionRegistry.scala:148-157,350-366`). Оба решения несут lineage-набор —
  другие открытые вкладки того же `rootId` в порядке создания
  (`SessionRegistry.scala:97-114,603-611`). Удаление прерывает consumer-волокно
  (оно разрегистрирует hub-очередь) и атомарно чинит фокус; идемпотентно при
  двойном закрытии (`SessionRegistry.scala:562-601`).
- `hide(tabId)` — убрать вкладку из UI, не трогая runtime
  (`SessionRegistry.scala:159-162,368-372`). `rename(tabId, label)` — только
  отображаемая метка (`SessionRegistry.scala:164-168,374-375`).
- Навигация — ОДИН вызов: `focus`/`focusNext`/`focusPrev` (кольцо по порядку
  создания, с переносом) (`SessionRegistry.scala:173-188,530-560`).
- `stop()` нет: consumer-волокна прерываются `close`/`hide`; реестр —
  process-lifetime объект (`SessionRegistry.scala:131-134`).

## 3. Таб-шелл TabShell

`TabShell` — тонкий JLine-цикл над реестром + hub
(`TabShell.scala:7-25` в `modules/ui/src/main/scala/raider/ui/TabShell.scala`):

- ОДИН писатель/читатель: всё видимое идёт через `ShellTerminal.printAbove`
  (JLine printAbove — draft переживает фоновые записи); raw println в сессии
  отсутствует (`TabShell.scala:11-14`;
  `ShellTerminal.scala:12-33,41-49` в
  `modules/ui/src/main/scala/raider/ui/ShellTerminal.scala`). EOF ведёт себя
  как `:quit`; idle Ctrl+C очищает текущую строку и не трогает вкладки/draft'ы
  (`TabShell.scala:66-72`; `ShellTerminal.scala:24-30,59-65`).
- Парсинг/рендеринг чистые: `CommandRouter` (`CommandRouter.scala:3-6`),
  `TabBarRenderer` (`TabBarRenderer.scala:3-5`),
  `ShellFeedback.render` (`ShellFeedback.scala:73-113` в
  `modules/ui/src/main/scala/raider/ui/`).
- Фоновый pump: подписка hub на просматриваемый job и перерисовка таб-бара
  (label + status + unread) на КАЖДЫЙ доставленный конверт; волокна scoped —
  прерываются со scope шелла (`TabShell.scala:130-174`).

## 4. Команды

Палитра — ровно эти команды (`CommandRouter.scala:11-24`; помощь `:help` —
`TabBarRenderer.scala:23-37`, строка-подсказка под каждым рендером бара —
`TabBarRenderer.scala:39-41`):

| Команда | Поведение |
| --- | --- |
| `:tabs` | таб-бар: порядок создания, `*` — фокус, статус сессии в едином формате `StatusText` (RAI-097: `<body> \| <counters>`, тот же рендер, что в CLI-чате; honest unknown — «cost unknown»), unread-бейдж (0 скрыт) (`TabBarRenderer.scala:8-20`, `statustext.scala`; пустое состояние — `TabBarRenderer.scala:12`) |
| `:new <label>` | новая вкладка с синтетическим job/root (см. §1); без аргумента — типизированная ошибка (`ShellCommand.scala:60-63`) |
| `:switch <n\|label>` | 1-based номер (как в `:tabs`), точная метка, затем уникальный префикс; неоднозначность/неизвестное — типизированная ошибка с альтернативами (`CommandRouter.scala:35-39,118-148`) |
| `:next` / `:prev` | фокус на следующую/предыдущую вкладку (сброс unread) (`ShellExecutor.scala:104-119`) |
| `:rename <label>` | переименовать фокусную вкладку, только UI (`ShellExecutor.scala:120-129`) |
| `:close` | закрыть фокусную вкладку (флоу — §5) (`ShellExecutor.scala:130,164-200`) |
| `:plugins` | экран загруженных плагинов name/version/active из PURE-провайдера; без плагинов — честное `(no plugins)` (`ShellExecutor.scala:133-134`; `ShellViews.scala:32-46,110-124`) |
| `:state <scope>` | usage/квота скоупа; без store — типизированная подсказка, не ошибка (`ShellExecutor.scala:135-140`; `ShellViews.scala:126-138`) |
| `:aux` | единственная вспомогательная вкладка (§6) (`ShellExecutor.scala:141-148`) |
| `:help` | палитра (`ShellExecutor.scala:131-132`) |
| `:quit` | выход из шелла (`ShellFeedback.scala:69,113`) |
| любой ввод без `:` | draft фокусной вкладки (byte-exact), эхо-фидбек (`CommandRouter.scala:52-55`; `ShellExecutor.scala:150-162`; `ShellFeedback.scala:96-97`) |

Ошибки ввода — типизированные с подсказками: неизвестная команда, пропущенный
аргумент, нет открытых вкладок, плохой номер/метка, неоднозначный префикс
(`ShellCommand.scala:46-92`). Ошибки реестра никогда не покидают executor —
превращаются в `ShellFeedback.Failure` (`error: ...`,
`ShellExecutor.scala:55-58`).

## 5. Флоу `:close` и отмена

Реестр возвращает типизированное решение; выбор для живой вкладки делает
`CloseChoiceResolver` (`ShellExecutor.scala:8-14,164-175`):

- `Closed(affected)` — job терминален по hub-снимку: вкладка удалена, перечислены
  другие открытые вкладки того же root (`ShellFeedback.scala:86-87`).
- Живая вкладка — диалог `[h]ide / [c]ancel-request`
  (`TabShell.scala:28-32`): `h`/`hide` → `registry.hide` (job продолжает
  работать, вкладка исчезает из `:tabs`; `ShellFeedback.scala:88-91`);
  `c`/`cancel` → `TabCloseChoice.CancelRequested` — это ТОЛЬКО ОТЧЁТ: сам слой
  UI никогда не отменяет, не прерывает и не публикует job; вкладка остаётся
  открытой (`ShellFeedback.scala:32-40,92-95`; `ShellExecutor.scala:164-198`).
  Прямого cancel-binding к runtime в UI-слое нет (RAI-062 не реализован).
- Интерактивный resolver перепрашивает до 3 раз; невалидный ответ, EOF или
  мёртвый терминал дают безопасный недеструктивный default —
  `CancelRequested` (report-only) (`ShellExecutor.scala:16-53`).

## 6. Вспомогательная вкладка `:aux`

- Ровно ОДНА aux-вкладка: чистый `AuxTabProvider` (`title`, `available`,
  `render`) подключается при построении шелла; default — none (честное «вкладки
  нет») (`AuxTab.scala:3-17,18-33` в
  `modules/ui/src/main/scala/raider/ui/AuxTab.scala`).
- `render` вызывается по требованию — один раз за показ, никогда при
  построении, никогда спекулятивно; `available = false` рендерит типизированную
  подсказку и содержимое не читает (`AuxTab.scala:9-15,23-27`;
  `ShellFeedback.scala:59-65,105-108`).
- Вкладка display-only: без drafts; переключение на неё и обратно не трогает
  реестр сессии — фокус/unread/draft остаются у сессионных вкладок
  (`AuxTab.scala:13-16`; `ShellCommand.scala:31-36`).
- Рендер — один блок: заголовок + строки (CRLF → LF, завершающий перенос —
  терминатор) через один `printAbove` (`AuxTab.scala:39-57`).

## 7. Экраны `:plugins`/`:state`

UI не выполняет IO сам: данные приходят через PURE-провайдеры `ShellViews`,
подключаемые при построении шелла; провайдеры тотальные — не бросают; отсутствующий
store — `None` (`ShellViews.scala:9-15,32-46`). Конвенция scope-строк:
`""`/`"agent"` — приватный скоуп агента, `root:<rootId>` — общий blackboard
рана, `global` — явный cross-run; прочая строка — `None`
(`ShellViews.scala:48-60`). Адаптер реального `StateStore` (RAI-094) отдаёт
живые usage/квоты (`ShellViews.scala:62-102`).
