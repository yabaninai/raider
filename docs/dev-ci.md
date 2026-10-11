# Developer CI (RAI-045.a) — raider-fast workflow

Status: реализован минимальный ранний срез RAI-045.a. Родительская карточка
[RAI-045](tasks/m2-p2.md) остаётся открытой; этот документ описывает только
срез `.a` и не заменяет её acceptance (DEV-CI-01..03).

## Что входит (workflow `.github/workflows/raider-fast.yml`)

Один job `fast` на `ubuntu-latest`, триггеры `push` и `pull_request`,
таймаут джобы **30 минут** (`timeout-minutes: 30`).

Шаги (все — фактические Makefile-таргеты):

1. `actions/checkout@v4`, `actions/setup-java@v4` — **JDK 21, distribution
   `temurin`** (пин повторяет существующий `.github/workflows/ci.yml`),
   `coursier/cache-action@v6`.
2. `make bootstrap` — тянет pinned toolchain jars (Scala 3.9.0, ZIO 2.1.26,
   zio-json 0.10.0, JLine 4.0.14, coursier interface) из Maven Central в
   `artifacts/toolchain` (`scripts/bootstrap.sh`, идемпотентен). Сетевой
   доступ здесь — осознанное исключение, допустимое для CI: скачиваются
   только пиновые артефакты с фиксированными координатами, без снапшотов.
3. `make compile` — `sbt --batch compile`.
4. `make quality-changed` — классификация изменённых файлов quality-раннером
   (git-mode), без исполнения.
5. `make quality-changed QUALITY_EXECUTE=1` — исполнение активных гейтов;
   упавший/пропущенный гейт или ошибка сканера валит шаг (continue-on-error
   не используется).

Гоняются только **offline-гейты**. Платные/live provider-вызовы в workflow
отсутствуют по построению: ни один шаг не запускает `raider-chat`,
`raider-run`, spike-и или что-либо, требующее API-ключей.

## Что НЕ входит (scope родителя RAI-045)

- полный ночной набор (`make quality-full`), soak, release lanes;
- GitLab CI (`.gitlab-ci.yml`) и `integrations/development-ci/**`;
- upload артефактов/логов при падении, fingerprint-верификация между jobs,
  nightly full Linux+macOS матрица, compiler/package smoke;
- PR risk gates / merge-base логика;
- fork secret isolation и защищённая policy для форков;
- acceptance DEV-CI-01 (манифесты/логи чистой сборки), DEV-CI-02
  (fail-closed на scanners/prerequisites/stale cache), DEV-CI-03
  (stale/concurrency/fork policy) — это приёмка родителя, а не среза `.a`.

Наличие YAML-файла **не** заявляет исполненный CI (non-goal родителя:
«claiming executed CI от YAML only»): первая фактическая прогонка
фиксируется отдельно с evidence.

## Ограничения

- **PTY-гейты не здесь.** `repl_pty_smoke.py`, `renderer_pty_probe.py`,
  `tabs_pty_probe.py` и cli/repl smoke требуют интерактивного терминала;
  на hosted `ubuntu-latest` они не выполняются — для них нужен self-hosted
  runner (отдельная работа в scope родителя).
- `sbt` ожидается предустановленным на GitHub-hosted ubuntu runner — то же
  допущение, что и в существующем `ci.yml` (workflow сам sbt не ставит).
- Файл `.github/workflows/*.yml` не сматчен в `path_profiles` реестра
  quality-раннера (`scripts/quality-policy/registry.json`) и классифицируется
  как `__unmapped__` до расширения реестра отдельной карточкой; на исполнение
  гейтов самого workflow это не влияет.
- Кэш `coursier/cache-action@v6` ускоряет дефолтный coursier-кэш sbt;
  `COURSIER_CACHE=/tmp/cc-master` из Makefile на ephemeral CI-диске не
  персистентен и не претендует на кэширование этим action'ом.
