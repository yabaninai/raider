package hello

import raider.core.{RaiderError, RecoveryClass, Tool, ToolCapabilities}
import raider.core.plugin.{
  CapabilityRef,
  PluginDescriptor,
  PluginVersion,
  RaiderPlugin
}

import zio.Scope
import zio.ZIO
import zio.json.{DecoderOps, EncoderOps}

/** RAI-096 SDK fixture: the smallest useful plugin — one STATELESS tool.
  *
  * The plugin entry (`HelloToolPlugin`) implements
  * `raider.core.plugin.RaiderPlugin` and only carries the descriptor; the tool
  * implements the frozen `raider.core.Tool` contract (JSON string in/out,
  * `ZIO[Scope, RaiderError, String]`). Nothing here loads, dispatches or
  * touches the host registry at class-init: a LAZY descriptor (ADR-025) — the
  * host decides when/whether to instantiate.
  *
  * Discovery contract (see docs/plugins.md): the jar ships
  * `META-INF/raider-plugin.json` (the install manifest pinning the payload
  * sha256) plus
  *   - `META-INF/services/raider.core.plugin.RaiderPlugin` -> HelloToolPlugin
  *     (exactly one SPI entry), and
  *   - `META-INF/services/raider.core.tool$Tool` -> HelloGreetTool (the
  *     provided set must EQUAL the declared tool capabilities).
  */
class HelloGreetTool extends Tool:

  def name: String = "hello.greet"

  def version: Int = 1

  def description: String =
    "stateless greeting; args {\"name\": \"...\"} (optional) -> {\"message\": \"hello[, name]\"}"

  def recovery: RecoveryClass = RecoveryClass.ReadOnly

  def timeoutMs: Long = 1000L

  def capabilities: ToolCapabilities = ToolCapabilities(concurrentSafe = true)

  override def parametersJsonSchema: String =
    "{\"type\":\"object\"," +
      "\"properties\":{\"name\":{\"type\":\"string\"," +
      "\"description\":\"who to greet\"}}," +
      "\"additionalProperties\":false}"

  def invoke(argumentsJson: String): ZIO[Scope, RaiderError, String] =
    argumentsJson.fromJson[Map[String, String]] match
      case Left(err) =>
        ZIO.fail(
          RaiderError.InputValidation(s"hello.greet args: ${err.take(120)}")
        )
      case Right(args) =>
        val who = args.getOrElse("name", "").trim
        val message = if who.isEmpty then "hello" else s"hello, $who"
        ZIO.succeed(Map("message" -> message).toJson)

class HelloToolPlugin extends RaiderPlugin:

  def descriptor: PluginDescriptor = PluginDescriptor(
    name = "hello-tool",
    version = PluginVersion(1, 0, 0),
    minApiVersion = 1,
    maxApiVersion = 1,
    capabilities = List(CapabilityRef("tool", "hello.greet"))
  )
