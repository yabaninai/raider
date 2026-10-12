# W22-REVIEW — ревью хвоста W20–W21 (lane W22-R1, 2026-10-12)

Reviewer: lane-worker W22-R1. Режим: READ-ONLY review, WORKTREE
(`raider-wt-1`, ветка `lane/w22-r1`, base `94e9f5b`). Код ревьюимых объектов не
изменялся; после всех проверок `git status --short` пуст (рабочее дерево
чисто, `artifacts/` и `RECORD.md` gitignored). Артефакты lane: этот файл
(`reviews/W22-review.md`) и `RECORD.md` в корне worktree.

Вердикт: **CHANGES-REQUIRED** — одна находка **F-W22R1-1** (объект 1,
оставленный отладочный `println` в продуктовой диагностике). Объект 1 в части
RunDiffReport — ACCEPT; объекты 2 и 3 — ACCEPT.

---

## Объект 1 — WorkspaceIsolation + RunDiffReport + спеки (L52, LIN-06C)

`modules/cli/src/main/scala/raider/cli/lineage/WorkspaceIsolation.scala`
`modules/cli/src/main/scala/raider/cli/lineage/RunDiffReport.scala`
`modules/cli/src/test/scala/raider/cli/lineage/WorkspaceIsolationSpec.scala`
`modules/cli/src/test/scala/raider/cli/lineage/RunDiffReportSpec.scala`

Проверено и зелёное:

- **Механика изоляции честная**: staging во временном каталоге + `ATOMIC_MOVE`
  (rename(2) того же FS) — наблюдатель `parent` не видит полускопированное
  дерево; no-fsync оговорен в scaladoc как ordering-visibility, не durability
  (WorkspaceIsolation.scala:36-38). Symlink/special-записи ОТКЛОНЯЮТСЯ
  (`CopyFailed`/`IllegalStateException`), а не копируются молча (41, 160-171);
  снапшот ограничен по числу файлов и ПАДАЕТ typed, а не обрезается —
  усечённый снапшот сфальсифицировал бы чистый `verifyUntouched` (47-51,
  126-130). Это правильная honesty-дисциплина.
- **`verifyUntouched` семантика полная**: modified/deleted/added ловятся
  (байт-сравнение `Arrays.equals` + повторный обход), первый offender — в
  детерминированном порядке сортированных относительных путей (197-223);
  добавленные пустые каталоги игнорируются согласно контракту «гарантия по
  БАЙТАМ файлов» (56-57). IO-ошибки поверх трёх typed-кейсов НЕ маскируются
  как «untouched» (59-60). Tampered несёт workspace-ОТНОСИТЕЛЬНЫЙ путь.
- **Спека LIN6-C — настоящая, не tautology**: реальные temp-dir фикстуры на
  каждый прогон с рекурсивной уборкой; мутации инжектируются ТОЛЬКО в копию
  (rewrite+delete+add), оригинал верифицируется байт-в-байт (Spec 79-96);
  Tampered-ассерты точны до пути (`sub/b.txt`, `a.txt`, `new.txt`);
  уникальность target на каждый `isolate` — pinned (copy2 != copy);
  zio-test-специфика (ленивый `assertTrue` после финализаторов) честно
  задокументирована и обойдена hoisting-ом (Spec 26-29).
- **RunDiffReport — ACCEPT без оговорок**: `render` — чистая функция
  аргументов, байт-стабильность pinned повторным рендером (Spec 81-97, 8 строк
  дословно); путь дивергенции рендерится в НОРМАЛИЗОВАННЫХ id (`root ->
  job-1:n4`) — согласовано с `compareNormalized`; `fromGraphs` на совпадающих
  графах — `Left` («Match — не отчёт») с общим normalized-хешем; счётчики
  expected/actual точны (2/1 в missing-node кейсе). Ничего кроме
  `GraphDiff`/`Meta` не читает — детерминизм по построению.

### F-W22R1-1: отладочный `println` оставлен в `verifyUntouched` — stdout-мусор и утечка абсолютных путей в диагностику

- **Где:** WorkspaceIsolation.scala:145-148.
- **Механизм.** `verifyUntouched` при КАЖДОМ вызове безусловно печатает в
  stdout `DEBUG-verify original=<absolute path> isDirectory=… exists=… cwd=…`.
  Это забытый debug трассировки lane L52:
  1. модуль promises диагностику без абсолютных путей окружения
     («workspace-RELATIVE path … no absolute environment paths leak into
     diagnostics», WorkspaceIsolation.scala:19-21) — scaladoc противоречит
     коду по соседству;
  2. `println` в библиотечной механике, вызываемой live re-run харнесом,
     загрязняет продуктовый stdout/CLI-вывод и любой capture-канал отчёта;
  3. `scalafmt`/`scalafix` такой вывод не ловят (проверено: текущие гейты
     зелёные с этим `println`) — то есть без ревью находка ушла бы в main.
- **Reproducer (наблюдение, без правки кода).** Любой вызов печатает строку;
  в спеке это видно на LIN6-C-01/02 (4 вызова = 4 DEBUG-строки в тестовом
  выводе прогона `raiderCli/test`; в зафиксированном выводе прогона настоящей
  ревью-сессии строки `DEBUG-verify original=/var/folders/...` присутствуют).
  Verdict-канал при этом НЕ повреждён (Right/Left корректны) — находка
  диагностической гигиены, не корректности.
- **Требуемое изменение (минимальное, отдельной карточкой фикса или в составе
  следующей карточки владельца объекта):** удалить строки 145-148 целиком
  (объявление `println(...)`); поведение `verifyUntouched` не меняется — все 4
  теста LIN6-C остаются зелёными. По желанию — pin-тест «no stdout noise»
  (захват `System.out` на одном вызове), чтобы регрессия не вернулась.

Итог по объекту: WorkspaceIsolation — CHANGES-REQUIRED (F-W22R1-1, фикс в одну
строку, механика и спека Sound); RunDiffReport — ACCEPT.

---

## Объект 2 — PhaseReplay + spec (L53, LIN-07-full) — ACCEPT

`modules/cli/src/main/scala/raider/cli/lineage/PhaseReplay.scala`
`modules/cli/src/test/scala/raider/cli/lineage/PhaseReplaySpec.scala`

- **Порядок фаз детерминирован и честен**: BFS roots→children, каждый уровень
  сортирован `(seq, id)`, visited-guard по id строки — терминация на
  malformed-графах гарантирована (PhaseReplay.scala:73-88); pinned тестом
  LIN7-F-01 (обе фазы в порядке seq 1, 4) и defensive-тестом: фаза внутри
  parent-цикла недостижима из корней, `phases` не виснет (Spec 479-502).
- **`NoPhases` typed и ДО lookup** — ровно как требует карточка: пустой список
  фаз даёт `Left(NoPhases)` даже для СУЩЕСТВУЮЩЕГО не-phase id
  (PhaseReplay.scala:102, тест LIN7-F-02 на `m1`).
- **`NodeNotFound` называет ЗАПРАШИВАЕМЫЙ id** в обоих случаях (нет узла /
  узел не phase) — синтезированных вердиктов не бывает (121, тест typed
  errors).
- **Независимые вердикты — по-настоящему**: каждый phase replay заново
  синтезирует срез через `SubtreeReplay.subtree` и верифицирует через
  `SubtreeReplay.verify` (никакого переиспользования чужих вердиктов); тест
  LIN7-F-01 доказывает: p1 `PartiallyResolved` (точный `Unresolved("m1",
  "d-missing")` + tool-обязательство), p2 при этом полностью `Resolved`, и
  `replayPhase(p2 alone) == replayAll-строка` (Spec 395-408). LIN-7
  «частичный» гарантия подтверждена.
- **Child-строки — single source of truth**: rows выводятся из ТОГО ЖЕ
  subtree-вердикта (`failures`/`tools` map), один row на DIRECT child в
  порядке `(seq, id)`; grandchild-отказы остаются в whole-slice outcome, child
  судится по своему dataRef (154-182) — согласовано со scaladoc и pinned
  точными векторами (`Obligation("dg1", None)` для delegation, `Resolved`
  для модели).
- **Golden-интеграция настоящая**: замороженный `contracts/lineage/golden/
  deep-tree` парсится с диска продакшн-кодеками (никакой пересборки в коде,
  Spec 79-97); slice единственной фазы === всё дерево, `graphHash ==
  GraphHash(graph)`, sha256-hex форма проверена (Spec 436-465).
- **Детерминизм**: повторные прогоны дают идентичные вердикты (LIN7-F-03);
  ни часов, ни рандома, ни IO в основном пути.
- **`replayAll` честно частичный**: фаза с malformed-циклом не имеет строки,
  reason доступен typed через `replayPhase` — задокументировано в scaladoc
  (126-127) и pinned defensive-тестом. Наблюдение (не блокер):
  `replayPhase` внутри `replayAll` каждый раз заново обходит `phases(graph)`
  ради NoPhases-гейта — O(phases×graph); для fixture-sized графов с
  `SnapshotMaxFiles`-подобными границами это приемлемо, при масштабировании
  — кандидат на локальный memo (за владельцем объекта, не в этой карточке).

Спека не tautology: точные вердикт-векторы, точные хеши, отрицательный
контроль (`isSha256Hex`), настоящая заморозка с диска.

---

## Объект 3 — GraphComparator: rename-инвариантная canonical job map (L54, F-L51-1) — ACCEPT

`modules/runtime/src/main/scala/raider/runtime/lineage/replay/GraphComparator.scala`
`modules/runtime/src/test/scala/raider/runtime/lineage/replay/GraphComparatorSpec.scala`

### Reproducer W20 — воспроизведён, теперь ЗЕЛЁНЫЙ

Reproducer F-L51-1 из W20-review выполнен в составе committed-спеки:
`FL51-01` строит те же G1/G2 (root `a` + Delegation→`b`; против root `y` +
Delegation→`x`, равные seq) и ассертит
`compareNormalized(g1, g2) == Right`, `normalize(g1) == normalize(g2)`,
нумерацию ровно `Vector(job-1:n1, job-2:n1, job-2:n2)` и `childJobId =
job-2` (Spec 500-584). Прогон:
`sbt --batch "raiderRuntime/testOnly raider.runtime.lineage.replay.GraphComparatorSpec"`
→ **19 passed / 0 failed** (было: W20 reproducer падал с KindMismatch —
ложная дивергенция на структурно одинаковых графах).

### scaladoc == код (сверено построчно)

- «root job → job-1; каждый другой job — номером ПЕРВОЙ Delegation, ведущей
  к нему; breadth walk по дереву делегаций; узлы каждого job в per-job `seq`
  порядке» — код: корни = job без входящих `Delegation.childJobId`
  (211-245), очередь BFS, per-job `.sortBy(_.seq)` БЕЗ id-тай-брейка —
  уникальность seq внутри job гарантирует SeqGap-инвариант редуктора
  (216-217, 248-260). Сырые id в этой нумерации не участвуют.
- «Raw ids — hence job NAMES — never take part in any ordering» — честно ДЛЯ
  REDUCER-SHAPED графа: единственное место, где `(seq, id)`-сортировка ещё
  остаётся (`known`, 228-230) — порядок FIRST-SEEN корней, а в reducer-форме
  корень ровно один (порядок не наблюдаем). Дегенеративный фолбэк
  (несколько независимых корней) scaladoc явно carve-out-ит: «deterministic,
  but only the delegation-tree shape carries the rename-invariance promise»
  (163-167, 197-200) — обещание и код совпадают, обещание НЕ шире кода.
- «An EMPTY child subtree (silent child job) still gets a label from its
  delegation» — register+enqueue на Delegation-узле независимо от наличия
  узлов (254-258); pinned FL51-01b (`ghost`/`mm` получают label).
- «Emission order … preserved (parents still precede children, so the
  normalized graph re-validates)» — `normalize` переписывает id 1:1 без
  пересортировки вектора (272); pinned точным вектором id в FL51-01c.
- Hash-дисциплина не тронута: `Left` несёт GraphHash ОБЕИХ нормализованных
  форм, `Right` — тот же хеш; structure-equal ⇒ hash-equal инвариант LIN5-B-03
  не ослаблен (старые сюиты зелёные, полный raiderRuntime 330/0).

### Усиление против замечаний W20

Замечание W20 «corpus-проперти не сэмплит adversarial-переименования»
закрыто: в LIN-06B-1 сюиту добавлена property «adversarially renamed jobs
(alphabet order REVERSED) match normalized for every seed» — переименование
`root→aaa-*, child-1→ccc-*, child-2→bbb-*` (обратный алфавит) по всем 20
seeds с sanity-ассертом, что raw compare ДЕЙСТВИТЕЛЬНО видит identity drift
(Spec 448-495). Плюс FL51-01b (своп алфавитного порядка двух детей при
сохранении порядка делегирования) и FL51-01c (трёхуровневое дерево, четыре
job сталкиваются на seq 1, полное обращение алфавита p→e, q→d, r→c, s→b,
t→a) — нумерация доказанно чистая функция формы дерева.

Наблюдение (не блокер): делегационный ЦИКЛ (A→B→A — дегенеративная форма,
вне редукторного shape) оставляет карту пустой → id не переписываются;
поведение детерминировано и покрыто общей оговоркой preconditions
(«reducer's … shape», 169-173). Фиксации spec-ом не требуется для карточки.

---

## Сводка по критериям

| Критерий | Вердикт | Комментарий |
| --- | --- | --- |
| (a) wire-freeze | OK | Ни один объект не расширяет события (13 payload-кейсов) или lineage-data.json v1: только чтение; тестовые документы строятся со `schemaVersion = 1`, новых полей нет. |
| (b) typed-честность | F-W22R1-1 | Все ошибки typed, без маскировки (snapshot падает, а не обрезается; IO не маскируется как «untouched»; NoPhases/Cycle/NodeNotFound честные). Единственная нечестность — debug `println` против собственного scaladoc-обещания диагностики. |
| (c) детерминизм | OK | Все обходы сортированы; нумерация job — чистая функция формы дерева; repeat-render/repeat-replay pinned; ни часов, ни рандома. |
| (d) не tautology | OK | Точные ассерты (пути, 8-строчный отчёт дословно, точные хеши/векторы), настоящие golden-фикстуры с диска, adversarial rename property; DEBUG-println шумит вывод, но ассерты от него не зависят. |
| (e) секреты | OK | Секретов нет; network/платных вызовов нет; temp-фикстуры с уборкой. Пограничное: F-W22R1-1 печатает локальные абсолютные пути в stdout — не секрет, но утечка окружения в диагностику (сложено в находку). |
| (f) ADR-027 | OK | Live re-run — против изолированной копии workspace (§2), diff-отчёт расхождений детерминирован, replay из записи без re-ask, инструменты — отложенные обязательства. |

## Проверки (точные команды и результаты, cwd = worktree)

- `git status --short && git branch --show-current` → чисто, `lane/w22-r1`.
- `sbt --batch "raiderRuntime/test" "raiderCli/test"` → exit 0;
  **raiderRuntime: 330 passed / 0 failed; raiderCli: 212 passed / 0 failed**.
- `sbt --batch "raiderRuntime/testOnly raider.runtime.lineage.replay.GraphComparatorSpec"`
  → exit 0; **19 passed / 0 failed**, включая FL51-01 (reproducer W20 —
  зелёный), FL51-01b, FL51-01c.
- `sbt --batch scalafmtAll "raiderCli/scalafixAll --check" "raiderRuntime/scalafixAll --check"`
  → exit 0; `git status --short` после — пусто (дерево не тронуто).
- `make quality-changed` → exit 0; «no changes vs HEAD (clean tree executes
  minimum fast)», profiles=[fast].
- `make quality-changed QUALITY_EXECUTE=1` → exit 0; status=passed, 10/10
  checks; evidence manifest:
  `artifacts/quality/20261012-024302-85359-0001/manifest.json`.

## Итог

- ACCEPT: объект 2 (PhaseReplay, L53), объект 3 (GraphComparator fix, L54 —
  reproducer W20 зелёный, scaladoc==код), RunDiffReport в составе объекта 1.
- CHANGES-REQUIRED: объект 1 (WorkspaceIsolation) — F-W22R1-1
  (WorkspaceIsolation.scala:145-148): удалить отладочный `println`; фикс в
  одну строку, механика и все тесты остаются зелёными. Fix — отдельной
  карточкой фикса/следующей карточкой владельца объекта, не этой review.
