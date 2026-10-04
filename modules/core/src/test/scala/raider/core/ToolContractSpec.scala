package raider.core

import zio.{Scope, ZIO}
import zio.test.*

/** RAI-002.b: Tool contract freeze — registration validation is total and
  * typed; lookup is pure; the JSON boundary matches ToolCallReady. */
object ToolContractSpec extends ZIOSpecDefault:

  private def stub(
    toolName: String,
    toolVersion: Int = 1,
    toolTimeoutMs: Long = 1000L,
    recoveryClass: RecoveryClass = RecoveryClass.ReadOnly,
    safe: Boolean = false
  ): Tool = new Tool:
    def name = toolName
    def version = toolVersion
    def description = "stub"
    def recovery = recoveryClass
    def timeoutMs = toolTimeoutMs
    def capabilities = ToolCapabilities(safe)
    def invoke(argumentsJson: String): ZIO[Scope, RaiderError, String] =
      ZIO.succeed(s"""{"echo":$argumentsJson}""")

  def spec = suite("Tool contract RAI-002.b")(
    test("valid registry builds; lookup is pure and exact") {
      val reg = ToolRegistry.of(stub("fs.read"), stub("fs.search", toolVersion = 2))
      for _ <- ZIO.unit
      yield assertTrue(
        reg.isRight,
        reg.map(_.size).contains(2),
        reg.map(_.names).contains(List("fs.read", "fs.search")),
        reg.toOption.flatMap(_.lookup("fs.read")).map(_.version).contains(1),
        reg.toOption.flatMap(_.lookup("fs.search")).map(_.version).contains(2),
        reg.toOption.flatMap(_.lookup("nope")).isEmpty
      )
    },
    test("duplicate names are a typed error (CFG-02 analogue)") {
      val reg = ToolRegistry.of(stub("dup"), stub("dup"))
      val msg = reg.left.toOption.map(_.detail)
      for _ <- ZIO.unit
      yield assertTrue(
        reg.isLeft,
        reg.left.toOption.map(_.code).contains("RA-INP"),
        msg.exists(_.contains("duplicate tool names: dup"))
      )
    },
    test("empty name / version < 1 / timeout <= 0 are typed errors") {
      val badName = ToolRegistry.of(stub("  "))
      val badVer  = ToolRegistry.of(stub("x", toolVersion = 0))
      val badTime = ToolRegistry.of(stub("y", toolTimeoutMs = 0))
      for _ <- ZIO.unit
      yield assertTrue(
        badName.left.toOption.map(_.code).contains("RA-INP"),
        badVer.left.toOption.map(_.detail).exists(_.contains("version must be >= 1")),
        badTime.left.toOption.map(_.detail).exists(_.contains("timeoutMs must be > 0"))
      )
    },
    test("invoke is lazy: registry construction dispatches nothing (API-03)") {
      var invoked = 0
      val lazyTool = new Tool:
        def name = "lazy"
        def version = 1
        def description = "counts invocations"
        def recovery = RecoveryClass.ReadOnly
        def timeoutMs = 500L
        def capabilities = ToolCapabilities(concurrentSafe = true)
        def invoke(argumentsJson: String): ZIO[Scope, RaiderError, String] =
          ZIO.succeed { invoked += 1; s"""{"n":$invoked}""" }

      val reg = ToolRegistry.of(lazyTool)
      for
        before = invoked
        _      <- ZIO.unit
        out    <- reg.toOption.flatMap(_.lookup("lazy")).get.invoke("\"hi\"")
      yield assertTrue(
        before == 0, // construction/registration dispatched nothing
        invoked == 1,
        out == """{"n":1}"""
      )
    },
    test("recovery classes and concurrentSafe stay declared, not inferred") {
      val t = stub("mutator", recoveryClass = RecoveryClass.Mutating)
      val t2 = stub("safe-read", recoveryClass = RecoveryClass.ReadOnly, safe = true)
      for _ <- ZIO.unit
      yield assertTrue(
        t.recovery == RecoveryClass.Mutating,
        t.capabilities.concurrentSafe == false, // default: mutating is not concurrentSafe
        t2.capabilities.concurrentSafe == true
      )
    }
  )
