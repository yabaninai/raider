# ADR-025: Plugin SPI — узкий versioned Scala SPI, manifest и preflight

Status: **Proposed** (freeze после independent review). Дата: 2026-10-09.
Owner: lane 5 (P4.A, [roadmap-12k](../roadmap-12k.md) §P4.A, 450 h package).
Карточка: [RAI-066](../tasks/m2-p2.md) (PLUG-01..03). Сохраняет силу tool
contract RAI-002.b ([`raider.core.tool`](../../modules/core/src/main/scala/raider/core/tool.scala))
и W13 «расширение без core edits» (working-product §2): этот ADR не меняет
`ToolRegistry` и не добавляет ни одного загрузчика в P4.A.

## 1. Контекст

Фактическое состояние: `ToolRegistry` иммутабелен (`ToolRegistry.build` —
единственный вход, все регистрации — typed `InputValidation` до использования);
в репозитории нет ни одного загрузчика jar, ServiceLoader-дискавери или
install-lifecycle; единственный способ добавить tool сегодня — пересобрать
Raider. Это блокирует W13 и Exit M2 (roadmap §P4): «выбрать локальный JAR →
увидеть source/version/capabilities → подтвердить доверие → использовать tool;
Raider не пересобирается».

Целевые требования: P4.B/C — install→use ≤3 действий без пересборки (P4.B:
manager/CLI install/list/check/enable/disable/update/remove/rollback с pinned
versions/checksums; P4.C: SDK/template/fixture plugins и совместимость API
версий). Ограничение честности из roadmap §P4: classloader — граница
совместимости, **не security sandbox**; Scala plugin имеет полномочия процесса;
`System.exit`/native crash не гарантированно перехватываются; workspace не
активирует код сам; plugin config не расширяет policy/root budget.

Карточка P4.A (RAI-066) сознательно узкая: ADR + skeleton типов/preflight в
`raider.core.plugin`; загрузчик, lifecycle и UI — отдельные карточки после
independent review этого ADR.

## 2. Решение

### 2.1 Narrow versioned descriptor-модель

Плагин — это дескриптор + реализации frozen SPI. Дескриптор несёт: `name`,
`version` (SemVer-подобный `MAJOR.MINOR.PATCH` без leading zeros; pre-release
метаданные в v1 не поддерживаются — их добавление отдельное ABI-решение),
`capabilities` (объявления по namespace: `tool` / `profile` / `hook` — имена
реестра; tools делят единый namespace иммутабельного `ToolRegistry`, profiles
и hooks — собственные namespace), `compatibility` — включительный диапазон
`[minApiVersion..maxApiVersion]` против `ApiVersion.current = 1`. Bump API
версии = ломающее изменение SPI (новый ADR), никогда тихая смена формы.

### 2.2 Manifest: JSON + sha256 pinning (НЕ security)

Установка описывается JSON-манифестом (`schemaVersion = 1`): имя, версия,
locator артефакта (в v1 — локальный JAR), `sha256` (64 lowercase hex), API
диапазон, capabilities. Sha256 пиннинг — целостность и воспроизводимость
(защита от битого файла и от тихой подмены между review и install). Он **не**
превращает недоверенный код в доверенный: доверие — отдельное явное
подтверждение владельца в P4.B UI, и эта граница заявлена в модели, а не
спрятана в installer.

### 2.3 Preflight до любого load

Вся решающая логика P4.A — чистые функции `Either[PluginError, Unit]`:

- **compatibility-пересечение**: `currentApi ∈ [minApiVersion..maxApiVersion]`,
  иначе typed `IncompatibleApi` → `RA-CAP` (refuse at preflight, не runtime
  surprise);
- **duplicate names**: plugin имена уникальны в co-install наборе; capability
  имена уникальны внутри namespace по всему набору и внутри одного дескриптора
  (clash tool имён обязан падать в preflight — `ToolRegistry.build` иммутабелен
  и дубликаты не принимает);
- **unknown capability**: kind вне `{tool, profile, hook}` — typed
  `UnknownCapability` → `RA-INP`;
- **валидность**: непустые name/artifact/capability-name, canonical sha256,
  `maxApiVersion ≥ minApiVersion ≥ 1`, версии ≥ 0 — typed `Malformed`/
  `InvalidVersion` → `RA-INP`.

`PluginError` — собственное sealed-семейство (RaiderError sealed; явная
конвертация `toRaiderError`, как `EventError` в ADR-024).

### 2.4 Controlled classloader (P4.B; здесь только контракт)

Загрузчик P4.B — parent-delegate classloader с явными экспортами: plugin видит
host API (raider-core) и объявленные экспорты, свои зависимости — из своего
jar; классы `raider-*`, zio/zio-json и JDK предоставляются хостом. Это граница
СОВМЕСТИМОСТИ (изоляция зависимостей/ABI), **не sandbox**: `System.exit`,
модификация JVM-стейта, native crash НЕ перехватываются — это честно
документируется в install flow, а не скрывается. Дефекты, которые можно
поймать, превращаются в typed failure; зависание — bounded cancellation с
честным сообщением. Доверенный код с полномочиями процесса — осознанное
решение; недоверенный код требует отдельного process-isolation проекта вне
этого бюджета.

### 2.5 Resource lifecycle (P4.B/C)

Обновление активного plugin применяется к новым sessions после drain активных
jobs; безопасный hot unload JVM-кода не обещается (обновлённые классы — новые
classloader'ы, старые экземпляры доедают свой lifecycle). Конфиг plugin не
расширяет policy/root budget: policy authority остаётся в core; hooks не
разрешают то, что запрещено core policy. Workspace не активирует код сам —
activation только явной install/enable владельцем; версия/конфигурация plugin
закреплены за run.

### 2.6 Зависимости и матрица совместимости

Риски ABI (Scala 3 ABI не стабилен между версиями) и dependency clash
закрываются связкой: хост pins scalaVersion/toolchain; plugin обязан объявить
`[minApiVersion..maxApiVersion]`; classloader изолирует plugin-зависимости от
host и соседей (§2.4); release notes/ADR ведут матрицу «API версия ×
совместимые toolchain/dependency правила». Clash и несовпадение ABI — typed
failure при preflight/load, никогда молчаливая подмена классов.

## 3. Рассмотрённые альтернативы

1. **Java ServiceLoader без манифеста** — неявная discovery при первом
   сканировании classpath: нет versioned handshake, нет preflight до load,
   нет честной границы доверия; отклонено.
2. **Скриптовые/не-JVM плагины** (JS/Lua) — не-Java SDK вне v1 (roadmap P4
   non-goals); отклонено.
3. **Отдельный процесс + протокол** (MCP-стиль, RAI-053) — другой cost/latency
   profile; правильно для недоверенных серверов, но не заменяет in-process
   Scala SPI для W13; отклонено как замена, остаётся параллельным путём.
4. **Не делать SPI (только core edits)** — нарушает W13/UX05 (пересборка на
   каждый custom tool); отклонено.

## 4. Последствия и риски

- Registry остаётся иммутабельным: install строит **новый** `ToolRegistry`
  (и profile/hook наборы), а не мутирует активный — обновление атомарно на
  границе sessions (§2.5).
- Риск ложного чувства безопасности от sha256: граница «integrity ≠ trust»
  заявлена в модели манифеста и повторяется в P4.B UI-тексте.
- Риск dependency clash внутри plugin jar (reflect/scripts, нативные либы) —
  матрица совместимости (§2.6) обязана сопровождаться; guarantee нет — только
  typed failure при обнаружении.
- Duplicate-name preflight разрешает конфликты до load, но не отменяет
  необходимость уникальности во время работы (порядок установки,
  enable/disable) — P4.B переиспользует эти же чистые функции, вторая
  валидация запрещена.

## 5. Frozen signatures (P4.B/C реализуют ровно это; изменения — новый ADR)

```scala
package raider.core.plugin

import raider.core.RaiderError
import zio.json._

object ApiVersion:
  val current: Int = 1

final case class PluginVersion(major: Int, minor: Int, patch: Int):
  override def toString: String  // "MAJOR.MINOR.PATCH"
object PluginVersion:
  val Pattern: String            // ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$
  def parse(s: String): Either[PluginError.InvalidVersion, PluginVersion]
  given JsonCodec[PluginVersion] // wire: "1.4.2"

enum CapabilityKind:
  case Tool, Profile, Hook
object CapabilityKind:
  def fromWire(kind: String): Option[CapabilityKind]  // "tool"|"profile"|"hook"

final case class CapabilityRef(kind: String, name: String)
object CapabilityRef: given JsonCodec[CapabilityRef]

final case class PluginDescriptor(
    name: String,
    version: PluginVersion,
    minApiVersion: Int,
    maxApiVersion: Int,
    capabilities: List[CapabilityRef]
)
object PluginDescriptor: given JsonCodec[PluginDescriptor]

final case class PluginManifest(
    schemaVersion: Int,           // supported: 1
    name: String,
    version: PluginVersion,
    artifact: String,             // opaque locator (v1: local JAR path)
    sha256: String,               // 64 lowercase hex; integrity, НЕ trust
    minApiVersion: Int,
    maxApiVersion: Int,
    capabilities: List[CapabilityRef]
)
object PluginManifest:
  val supportedSchemaVersions: Set[Int] = Set(1)
  given JsonCodec[PluginManifest]

sealed trait PluginError:                 // errors are data; effects нет
  def detail: String
  def toRaiderError: RaiderError          // Malformed/InvalidVersion/Unknown/
                                          // Duplicate -> RA-INP;
                                          // IncompatibleApi -> RA-CAP
object PluginError:
  final case class Malformed(what: String)
  final case class InvalidVersion(version: String, why: String)
  final case class UnknownCapability(kind: String)
  final case class DuplicateName(namespace: String, name: String)
  final case class IncompatibleApi(name: String, min: Int, max: Int, current: Int)

trait RaiderPlugin:                       // весь SPI v1: дескриптор; discovery/
  def descriptor: PluginDescriptor        // инстанцирование — P4.B loader

object Preflight:                         // всё чистое: Either, ноль IO
  def descriptor(d: PluginDescriptor): Either[PluginError, Unit]
  def compatibility(d: PluginDescriptor,
      currentApi: Int = ApiVersion.current): Either[PluginError, Unit]
  def manifest(m: PluginManifest): Either[PluginError, Unit]
  def install(m: PluginManifest,
      currentApi: Int = ApiVersion.current): Either[PluginError, Unit]
  def coinstall(ds: List[PluginDescriptor],
      currentApi: Int = ApiVersion.current): Either[PluginError, Unit]
```

## 6. Acceptance (RAI-066: PLUG-01..03)

- PLUG-01: манифест-модель roundtrip (zio-json, строковый wire версий);
  валидный SPI-дескриптор компилируется (тестовый `FixturePlugin extends
  RaiderPlugin`).
- PLUG-02: duplicate plugin/tool имена, невалидные версии (parse + компоненты),
  unknown capability, несоответствие API диапазона, malformed манифест — typed
  `PluginError` с проверенным маппингом `RA-INP`/`RA-CAP`.
- PLUG-03: skeleton без побочных эффектов — по дизайну: сигнатуры без
  effect-типов плюс constant-pool сканирование скомпилированных классов
  `raider.core.plugin` на IO/loader/classloader типы (`java.io.File`,
  `java.nio.file`, `ServiceLoader`, `URLClassLoader`, `ProcessBuilder`,
  `zio.ZIO`, …); ссылки на `java/io/Serializable` допустимы (маркер case
  классов, прав не даёт).

## 7. Не входит

Загрузчик jar и ServiceLoader-дискавери, install/rollback UI и CLI (P4.B),
hooks-исполнение и семантика hooks (отдельная карточка P4.B/C), security
sandbox и process isolation недоверенного кода, marketplace/биллинг, SDK не на
JVM-языках, второй scheduler/runtime, изменение `ToolRegistry`/gate policy.
Gates для RAI-066 — component stage (static/unit/contracts/docs).
