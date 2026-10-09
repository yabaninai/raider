# UX baseline protocol (RAI-070) — закреплённая машина, baseline без порогов

Карточка RAI-070 (M0, P0.C остаток / P9 prep). Harness: `scripts/quality/ux_baseline.py`
(stdlib-only Python). Отчёт: machine-readable JSON (`--out`, по умолчанию
`artifacts/ux-baseline/ux-baseline.json`), side-files кладутся рядом с отчётом.

**Главное ограничение протокола: этот harness НЕ заявляет пороги UX01–08**
(roadmap-12k §UX, gap matrix `2026-10-05-p0a-gap-matrix.md` §2). Он записывает
baseline-числа — machine-clock замеры на одной закреплённой машине, чтобы у
будущих карточек (P1/P2/P9) была точка отсчёта. Никакое число из этого отчёта
не является pass/fail против целевых порогов (UX03 p95 ≤ 250 ms, UX04 p95 ≤ 2 s
warm и т.д.), и не является human-usability evidence. Non-goals карточки:
human usability sessions, пороги-претензии, CI-обязательность.

## 1. Закрепление машины

«Закреплённая машина = эта macOS»: параметры фиксируются в КАЖДОМ отчёте
(`environment`), а не в документе — число читается только вместе со своим
прогоном. В `environment` попадают: `java -version` (первая строка), sbt
(версия из `project/build.properties` + строка runner), `TERM` окружения,
CPU count, platform/hostname, python, load average (1m/5m/15m), timestamp и
**repo fingerprint** — sha256 по inventory всего репозитория с ТЕМИ ЖЕ
исключениями, что у quality runner (`target/`, `artifacts/`, `.git/`,
`.opencode/`, …; см. `environment.repo_fingerprint.excludes`). Изменение
исходников после прогона инвалидирует привязку evidence — нужен новый прогон.

Пины прогона (samples, таймауты, child TERM, provider=mock) — в
`environment.pins`; их изменение делает прогоны несравнимыми напрямую.

## 2. Что измеряется

### 2.1 `measurements.ux03_event_visible_latency` — event→visible (прототип UX03)

Реальный REPL (`java -cp <classpath> raider.repl.Main`) под PTY, владельцем
которого является harness: winsize 40×120, ISIG выключен, Primary-DA запросы
JLine отвечаются, `NO_COLOR=1`, `TERM=xterm-256color` у ребёнка (не
зависит от ambient TERM), изолированный HOME, provider=mock. Техника — как в
`scripts/quality/repl_pty_smoke.py` / `renderer_pty_probe.py`.

Сэмпл: в master PTY записывается уникальный токен `z000`…`zNNN` (уникальность —
чтобы эхо нельзя было спутать с предыдущим вхождением); часы
(`monotonic_ns`) останавливаются, когда эхо токена появляется в очищенном от
ANSI выходном потоке. Затем строка очищается backspace'ами (REPL ничего не
eval'ит — сессия остаётся чистой). N=100 по умолчанию (`--samples`); бюджет
на сэмпл 5 s. Записываются: n, p50/p95/max/min/mean, **stddev (population)**
и сырые `samples_ms` (округление 3 знака); перцентили — linear interpolation
(правило renderer-spike). Таймаут сэмпла — не passed: статус `partial` с
`failed_samples` > 0, полный промах — `failed`.

### 2.2 `measurements.ux04_repl_start` — старт REPL (прототип UX04)

Одна итерация = пара запусков: **cold** — первый `java -cp <repl-test-classpath>
raider.repl.Main` до появления промпта `raider> ` в видимом потоке (PTY,
таймаут 30 s, как в карточке); **warm** — немедленно следующий второй запуск.
По умолчанию 5 итераций (`--start-samples`) → 5 cold + 5 warm; p50/p95,
stddev, сырые сэмплы (в секундах) записываются отдельно.

Честные ограничения метрики: warm здесь = тёплые OS page caches
(class-файлы/библиотеки уже в кэше) при каждом разе новом JVM-процессе; это
НЕ preloaded server и не JIT-прогрев одного процесса. «Баннер» REPL — это
первый видимый промпт; отдельного баннера процесс не печатает.

## 3. Classpath и honest skip

Порядок разрешения classpath (`classpath.source` в отчёте):

1. `RAIDER_REPL_CP` (env, как в `repl_pty_smoke.py`);
2. кэш успеха `<out>.classpath-cache.txt` (рядом с отчётом);
3. `sbt --batch --error export raiderRepl/runtime:fullClasspath`
   (бюджет `UX_SBT_TIMEOUT_S`, по умолчанию 900 s; `COURSIER_CACHE`
   пробрасывается как есть — сеть не используется самими замерами).

Если classpath недоступен, UX-замеры получают статус
`"skipped: no classpath (documented skip, не passed)"` с конкретной причиной —
**documented skip не является passed**, и отчёт всё равно пишется валидным
JSON с environment + skips (exit 0). Причина неудачи sbt кэшируется в
`<out>.classpath-fail.json` (детерминизм повторных прогонов); повторить
попытку — `--refresh-classpath`; отключить sbt совсем — `--no-sbt`.

## 4. Как повторять

```bash
# одиночный прогон (report + environment + baseline)
python3 scripts/quality/ux_baseline.py \
  --out /path/to/evidence/ux-baseline.json          # exit 0, если JSON записан

# валидность JSON
python3 -m json.tool /path/to/evidence/ux-baseline.json >/dev/null

# повторный прогон (идемпотентен: те же side-file имена перезаписываются)
python3 scripts/quality/ux_baseline.py --out /path/to/evidence/ux-baseline.json
```

### UXB-01 — JSON-baseline с параметрами среды

Один прогон: exit 0, `environment` заполнено (java/sbt/TERM/cpu_count/
timestamp/repo_fingerprint), `tests.UXB-01.status = "pass"`.

### UXB-02 — unsupported terminal: явная фиксация, не тихий skip

```bash
TERM=unknown-term python3 scripts/quality/ux_baseline.py \
  --out /path/to/evidence/ux-baseline-uxb02.json    # exit 0
```

В отчёте обязано появиться: `terminal.status = "unsupported"`,
`terminal.supported = false`, `terminal_unsupported = true`, finding с
пояснением — отсутствие support записано явно, а не молча пропущено. Дети
PTY продолжают работать с probe-owned `TERM=xterm-256color` (ambient TERM
влияет только на запись environment). Классификация: точное совпадение или
префикс известных семейств (xterm*, screen*, tmux*, vt100*, dumb, …);
`TERM=dumb` считается supported-limited (RENDER-03: plain fallback работает).

### UXB-03 — повторный прогон стабилен (дисперсия записана)

Два прогона подряд по §4. (1) Дисперсия: в каждом замере записан
`stddev_ms` (population) + сырые сэмплы. (2) Идентичность структуры:
рекурсивное множество ключей совпадает (значения и timestamp естественно
различаются):

```bash
python3 - <<'EOF'
import json, sys
def keys(o, p=""):
    ks = set()
    if isinstance(o, dict):
        for k, v in o.items():
            ks.add(f"{p}.{k}"); ks |= keys(v, f"{p}.{k}")
    elif isinstance(o, list):
        for v in o: ks |= keys(v, f"{p}[]")
    return ks
a = keys(json.load(open(sys.argv[1])))
b = keys(json.load(open(sys.argv[2])))
print("STRUCTURE_IDENTICAL" if a == b else
      f"DIFF only_a={sorted(a-b)} only_b={sorted(b-a)}")
EOF
```

## 5. Exit codes и идемпотентность

- exit 0 — отчёт записан (skips и честные `failed`/`partial` статусы
  допустимы и записаны в JSON); пропущенный замер никогда не помечается
  passed;
- exit 2 — записать отчёт не удалось;
- побочные файлы: `<out>.classpath-cache.txt`, `<out>.classpath-fail.json`,
  `<out>-transcripts/` (транскрипты PTY-сессий), `<out>.tmp` → атомарный
  `os.replace`. Повторный прогон перезаписывает их детерминированно.
