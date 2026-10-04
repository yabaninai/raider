<!-- Work record. Original in Russian; key results in English below. -->

# RAI-016.a: Owned process primitives — record

Дата: 2026-10-03. Writer: coordinator (root; lane-путь отменён по escalation).
Deps: FROZEN core (RaiderError) + RAI-014 Workspace containment (cwd-чек
переиспользует тот же realpath-подход).

## Deliverable

Production: `modules/tools/src/main/scala/raider/tools/process/ProcessTool.scala`

- Structured argv ТОЛЬКО (`ProcessBuilder(argv*)`, redirectErrorStream(false)) —
  shell-метасимволы проходят как литеральные данные (тест: `a; rm -rf / | cat`
  печатается буквально);
- env = `environment().clear()` + ТОЛЬКО allowlist∩values: никакого ambient
  наследования → нет унаследованных credential'ов. Политика строже «фильтра»:
  envValues с ключами вне allowlist → typed RA-INP ДО спавна;
- Output capture byte-bounded (`maxOutputBytes`, hard 8MiB; превышение —
  declared `truncated`), drain в отдельных потоках (deadlock-safe);
- Timeout: graceful `destroy()` → 2s grace → `destroyForcibly()` → 2s;
  `timedOut` declared; «unkillable within grace» фиксируется в stderr;
- `SandboxCapability` = negotiation type (Supported/Unsupported/Unknown);
  tool честно репортит Unknown; `sandboxRequired=true` при не-Supported →
  RA-CAP deny (никакого silent fallback);
- `CleanupGuarantee.DirectChildOnly` в КАЖДОМ outcome — это явное известное
  обязательство из RAI-001/ADR-015: убийство прямого child НЕ доказывает
  cleanup process-group/grandchildren. НЕ claim'ится то, что не доказано;
- cwd: containment как у Workspace (realpath, escape → RA-TOOLDENY).

Tests (6): PROC-01 echo+exit+bounds; env allowlist (ребёнок НЕ имеет PATH/HOME
родителя — проверка отсутствия наследования); shell-литеральность; byte-bound
truncation declared; timeout-kill за bounded wall time (sleep 30 @400ms);
PROC-02 typed negatives (empty argv / empty head / cwd escape / env-policy /
zero bound / sandbox deny).

## Проверки (exact)

- `sbt --batch "raiderTools/test"` → exit 0, 19/19 (read 8 + search 5 + process 6).
- `make quality-changed QUALITY_EXECUTE=1` → exit 0, 6/6 gates
  (artifacts/quality/20261003-142936/manifest.json; sbt-test: все модули).

## Находки в ходе карточки

1. Перевёрнутое направление env-политики (allowlist⊆values вместо
   values⊆allowlist) — поймано собственным negative-тестом, исправлено.
2. Sentinel-тест первоначально конфликтовал со строгой политикой (лишние
   значения отклоняются до спавна) — переделан на честную проверку отсутствия
   наследования от родителя (PATH/HOME).
3. -Werror deprecation (`StringBuilder.newBuilder`) → `new StringBuilder`.

## Known obligations (честно, не закрываются этой карточкой)

- Process-group/grandchild kill (RAI-016 parent, ADR-015) — зафиксирован как
  `CleanupGuarantee.DirectChildOnly`; реальный process-group kill — отдельная
  карточка (проверка требует platform contract, macOS/Linux различия).
- PID-reuse/foreign-process защита — за пределами slice (PROC-03 частично).
- Uncertain-статус при неубиваемом child: сейчас честно помечается в stderr +
  timedOut; отдельный typed `Uncertain` outcome — вместе с journal (RAI-027).
