<!-- Work record. Original in Russian; key results in English below. -->

# RAI-004 (MVP slice): work record

Status: done (review pending; карточка RAI-004 целиком — stage-aware full/milestone
семантика, coverage, scanner — остаётся открытой). Date: 2026-10-03 (UTC).

## Resulting behavior

Quality runner MVP: `scripts/quality/quality.py` (python — разрешённая область) с
protected registry `scripts/quality-policy/registry.json`:
- `changed [--base-manifest|QUALITY_BASE_MANIFEST]` — классификация изменений
  (added/modified/deleted/mode) по path→profiles матрице; пустой baseline → fast;
- `execute [--profiles]` — component-stage гейты (self/static/unit/contracts/docs/
  fast), честные exit codes, timeout, логи+sha256, evidence manifest (schema §5:
  source fingerprint, policy_sha256, toolchain, gates, test_summary);
- `verify MANIFEST --require P --fresh` — статусы, logs hashes, policy hash,
  свежесть fingerprint; фальшивый manifest отвергается;
- `self-check` (registry integrity + verify-negative) и
  `self_test_fault_injection.sh` (fake sbt/python3 → execute FAIL).
Makefile: quality-changed / quality-changed-execute / quality-profile
PROFILES=... / quality-verify MANIFEST= PROFILES= / quality-inventory.

## Commands (actual)

| argv | Exit |
| --- | --- |
| python3 scripts/quality/quality.py self-check | 0 |
| python3 scripts/quality/quality.py execute | 0 (5/5 gates passed; artifacts/quality/20261003-022434/) |
| python3 scripts/quality/quality.py verify <manifest> --require fast (freshness — default) | 0 |
| sh scripts/quality/self_test_fault_injection.sh | 0 (артефакт: artifacts/quality-fault-injection.log) |
| классификация: docs-only → [docs]; modules → [contracts,static,unit] | проверено |

## Limitations

- full/milestone профили ещё не определены (появятся на своих стадиях; runner уже
  отклоняет non-component профиль как not-executable, не как skip);
- static gate = sbt compile c -Werror; scalafmt/scalafix/scanner — следующая
  итерация RAI-004 после выбора версий;
- verify --fresh пересчитывает fingerprint по всему дереву (медленно на больших
  репо — приемлемо для MVP);
- coverage/JUnit-парсинг/test_summary из machine-readable отчётов — заглушка
  (считает гейты, не тесты) до ZIO Test (RAI-006).

## Round-2 independent review (read-only explore) — закрыт

Найдено 11 замечаний (1 HIGH, 2 MED-HIGH/MEDIUM критичных для false-green). Все
исправлены и перепроверены на финальном дереве:

| Finding | Fix | Проверка |
| --- | --- | --- |
| `make quality-changed QUALITY_EXECUTE=1` — зелёный no-op (HIGH) | Makefile: QUALITY_EXECUTE=1 вызывает execute | EXECUTE=0, 5/5 gates |
| Мёртвый `__unmapped__` guard: unmapped-файлы молча выпадали из гейтинга | execute падает со списком unmapped-файлов в diff-режиме | `execute-diff-unmapped=1`, сообщение со списком |
| Fingerprint включал `modules/*/target/**` (494/567 записей) | segment-based excludes на любой глубине | 0 target-файлов, 75 файлов в манифесте |
| `verify` без `--fresh` принимал stale | freshness — default; `--allow-stale` как явный historical-режим | stale→1, allow-stale→0 (на зелёном манифесте) |
| Overclaim «verify rejects fabricated» | Честная формулировка tamper-evidence | self-check вывод обновлён |
| boundary: `modulesX` матчился как `^modules/` | сегментная граница `alt + "/"` | modulesX → unmapped → execute FAIL |
| 27/27 vs факт 26/26; .PHONY; usage PROFILES; таймстампы/mode/dead code/timeout-decode; python3-режим fault-injection слаб | записи исправлены на 26; .PHONY дополнен; usage comma+space; started≠finished; mode по env; dead code удалён; TimeoutExpired str-безопасен; второй режим fake `sh` (compile-fixtures gate) | QFI: sbt+sh оба FAIL корректно |

Финальное evidence: `make quality-changed QUALITY_EXECUTE=1` → passed 5/5
(artifacts/quality/20261003-030303/), `verify --require fast` → OK fingerprint
fresh; путь до последнего зелёного манифеста: artifacts/quality/LAST-PASSING.

## Review and next task

Round-2 review: findings закрыты (таблица выше); фикс-раунд записан. Карточка
RAI-004 остаётся открытой (full/milestone профили, scanner, coverage, JDK policy).
Next: F1 — RAI-006 testkit (ZIO Test + scripted ModelBackend) и RAI-007 jobs.
