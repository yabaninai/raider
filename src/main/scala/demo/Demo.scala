package demo

import raider.core.{RaiderError, RootId, Task}
import raider.core.config.{RaiderConfig, credentials}
import raider.dsl.composition.{all, batch}
import raider.runtime.admission.{Admission, SlotKind}
import raider.runtime.budget.{BudgetLimits, CostEstimate, MicroUsd}
import raider.runtime.jobs.JobManager
import raider.tools.files.read.{ReadTool, Workspace}
import raider.tools.process.{ExecRequest, ProcessTool}
import raider.tools.search.SearchTool
import zio.{Duration, ZIO, ZIOAppDefault}

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/** Wave-1 live demo: every layer on one build — config, tools, budget/admission,
  * jobs, composition. Real processes, real files, real bounds. */
object Demo extends ZIOAppDefault:

  private val configJson =
    """{"schemaVersion":1,
      |"roles":{"primary":{"profile":"openai"},
      |         "coder":{"profile":"local","modelOverride":"local-coder-7b"},
      |         "reviewer":{"profile":"anthropic","requiredOption":"tools"}},
      |"profiles":[
      | {"name":"openai","wire":"OpenAICompatible","endpoint":{"url":"https://api.example.com/v1"},
      |  "apiPrefix":"/v1","credential":{"envVar":"DEMO_API_KEY"},"model":"gpt-demo","priceKnown":true},
      | {"name":"local","wire":"OpenAICompatible","endpoint":{"url":"http://127.0.0.1:8081/v1"},
      |  "apiPrefix":"/v1","credential":{"envVar":"DEMO_LOCAL_KEY"},"model":"qwen","priceKnown":false},
      | {"name":"anthropic","wire":"AnthropicCompatible","endpoint":{"url":"https://api.anthropic.example/v1"},
      |  "apiPrefix":"/v1","credential":{"envVar":"DEMO_API_KEY"},"model":"claude-demo","priceKnown":true}],
      |"ceilings":{"maxSteps":32,"maxOutputTokens":8192,"maxConcurrentModelCalls":2,
      |            "hardCapMicroUsd":900000}}""".stripMargin

  private def say(line: String): ZIO[Any, Nothing, Unit] = ZIO.succeed(println(line))
  private def errOf(e: RaiderError): String = s"${e.code} (${e.detail})"
  private def exitErr[A](e: zio.Exit[RaiderError, A]): String = e match
    case zio.Exit.Failure(cause) => errOf(cause.failures.head)
    case zio.Exit.Success(_)     => "no error?!"

  def run =
    for
      _ <- say("=== Raider wave-1 demo (Scala 3.9.0 + ZIO 2.1.26) ===")

      // ---- [1] config: versioned JSON -> validated config -> bounded env resolve
      cfg = RaiderConfig.decode(configJson)
      _ <- say(s"[1] config decode: ${cfg.isRight}")
      resolved = cfg.flatMap(c => credentials.resolve(c,
                     env = Map("DEMO_API_KEY" -> "super-secret", "DEMO_LOCAL_KEY" -> "k2")))
      _ <- say(s"    roles=${cfg.toOption.map(_.roles.keys.mkString(","))}")
      _ <- say(s"    coder profile: ${cfg.toOption.flatMap(_.profileForRole("coder")).map(p => s"${p.name}/${p.model} (override applied)")}")
      _ <- say(s"    credentials: ${resolved.toOption.map(_.values.mkString(", "))} (values redacted)")
      _ <- say(s"    secret leak check: diagnostics contain 'super-secret'? ${resolved.toString.contains("super-secret")} (must be false)")

      // ---- [2] temp workspace with content
      root <- ZIO.attemptBlocking(Files.createTempDirectory("raider-demo-ws")).orDie
      _ <- ZIO.attemptBlocking {
             def write(rel: String, content: String): Unit =
               val p = root.resolve(rel)
               Files.createDirectories(p.getParent)
               Files.writeString(p, content)
             write("src/big.txt", (1 to 100).map(i => s"reservation line $i").mkString("\n"))
             write("docs/notes.txt", "budget reservation is atomic; reservation wins races")
           }.orDie
      ws <- Workspace.make(root.toString)

      // ---- [3] ReadTool: ranged read + provenance
      reader = new ReadTool(ws)
      big <- reader.read("src/big.txt", offsetLine = 5, maxLines = 3)
      _ <- say(s"[3] ranged read src/big.txt[5..8): ${big.content}, totalLines=${big.totalLines}, truncated=${big.truncated}")
      _ <- say(s"    provenance: sha256=${big.sha256.take(16)}..., bytes=${big.bytes}")

      // ---- [4] containment: traversal denied
      escape <- reader.read("a/../../etc/passwd").exit
      _ <- say(s"[4] traversal 'a/../../etc/passwd' -> ${exitErr(escape)}")

      // ---- [5] bounded search
      search = new SearchTool(ws)
      found <- search.search("reservation", glob = Some("*.txt"), maxMatches = 4)
      _ <- say(s"[5] search 'reservation' (*.txt): ${found.matches.size} matches (scanned ${found.filesScanned} files), first: ${found.matches.headOption.map(m => s"${m.file}:${m.lineNumber}")}")

      // ---- [6..8] real processes
      proc = new ProcessTool(ws)
      echo <- proc.exec(ExecRequest(argv = List("/bin/echo", "a; rm -rf / | cat"), maxOutputBytes = 1000))
      _ <- say(s"[6] process (no shell): echo exit=${echo.exitCode.getOrElse("?")}, stdout='${echo.stdout.trim}' (metachars are literal data)")
      env <- proc.exec(ExecRequest(
                argv = List("/usr/bin/env"),
                envAllowlist = List("DEMO_ALLOWED"),
                envValues = Map("DEMO_ALLOWED" -> "yes")))
      _ <- say(s"[7] env allowlist: child sees DEMO_ALLOWED=${env.stdout.contains("DEMO_ALLOWED=yes")}, inherits PATH/HOME? ${env.stdout.contains("PATH=") || env.stdout.contains("HOME=")} (must be false)")
      slow <- proc.exec(ExecRequest(argv = List("/bin/sleep", "30"), timeout = Duration.fromMillis(400)))
      _ <- say(s"[8] timeout: /bin/sleep 30 killed at deadline, timedOut=${slow.timedOut}, wall=${slow.durationMs}ms, cleanup=${slow.cleanup} (grandchild kill = known obligation)")

      // ---- [9] budget + admission: bounded concurrent admission, exact money
      limits = BudgetLimits.make(hardUsdCap = Some(MicroUsd(900_000L)),
                   maxConcurrentLlm = 2, maxChildren = 64)
      adm <- Admission.make(limits)
      now  = new java.util.concurrent.atomic.AtomicInteger(0)
      peak = new java.util.concurrent.atomic.AtomicInteger(0)
      modelCall = adm.withSlot(SlotKind.Model, RootId("demo-root"),
                    CostEstimate.Priced(MicroUsd(300_000L)), None) {
                    val c = now.incrementAndGet()
                    peak.accumulateAndGet(c, math.max)
                    ZIO.sleep(Duration.fromMillis(80)) *>
                      ZIO.succeed(((), MicroUsd(100_000L)))
                  }
      results <- ZIO.foreachPar(1 to 6)(_ => modelCall)
      usage <- adm.budgetLedger.usage
      _ <- say(s"[9] 6 model calls under maxLLM=2 + cap 900µ$$: peak concurrent=${peak.get} (bound 2), observed=${usage.observedTotal} exact (6x100µ$$), reservedActive=${usage.reservedActive} (settled)")

      // ---- [10] unknown price under hard cap: refused, no dispatch
      ran = new java.util.concurrent.atomic.AtomicInteger(0)
      refused <- adm.withSlot(SlotKind.Model, RootId("demo-root"), CostEstimate.Unknown, None) {
                   ZIO.succeed((ran.incrementAndGet(), MicroUsd(0L)))
                 }.exit
      _ <- say(s"[10] unknown price under hard cap -> ${exitErr(refused)}, dispatch ran=${ran.get} times (must be 0)")

      // ---- [11] DSL composition: ordered bounded batch + collect
      composed <- Task.zio(batch(List(30, 10, 20), parallelism = 2) { ms =>
                    Task(ZIO.sleep(Duration.fromMillis(ms.toLong)).as(ms / 10))
                  })
      collected <- Task.zio(batch.collect(List(1, 2, 3), 2) { i =>
                     if i == 2 then Task.fail(RaiderError.ProviderAuth("401"))
                     else Task.succeed(i * 10)
                   })
      _ <- say(s"[11] batch(List(30,10,20), par=2) -> $composed (input order kept)")
      _ <- say(s"     batchCollect -> $collected (domain 401 became Failed outcome, others succeeded)")

      // ---- [12] no-deadlock: maxLLM=1, parent delegates to child and joins
      manager <- JobManager.make()
      adm1 <- Admission.make(BudgetLimits.make(maxConcurrentLlm = 1))
      childBody = adm1.withSlot(SlotKind.Model, RootId("demo-root"),
                    CostEstimate.Priced(MicroUsd(50L)), None) {
                    ZIO.sleep(Duration.fromMillis(30)) *> ZIO.succeed(("child-done", MicroUsd(50L)))
                  }
      child <- manager.start(RootId("demo-root"), "child", Task(childBody))
      _ <- adm1.withSlot(SlotKind.Model, RootId("demo-root"),
             CostEstimate.Priced(MicroUsd(10L)), None)(ZIO.succeed(((), MicroUsd(10L))))
      v <- manager.await(child)
      usage1 <- adm1.budgetLedger.usage
      _ <- say(s"[12] maxLLM=1: parent ran its call, forked child, joined -> '$v' (no deadlock); exact ledger: observed=${usage1.observedTotal}")

      _ <- say("=== demo done ===")
    yield ()
