package raider.dsl.workflow

import zio.test.*
import zio.test.Assertion.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import raider.core.RaiderError

import scala.util.control.NonFatal

/** RAI-155.c renderer unit checks (pure layer): STS validation refusals
  * (ADR-033 §8 R1/R3/R4 with the CWF-Rn detail prefix), determinism (identical
  * selection -> byte-identical artifacts, §4.3), escape discipline (`\uXXXX` in
  * string literals, `\xHH` only inside `//` comments — the RAI-151 precedents),
  * the §6.2 layout/write discipline (staging + rename, never overwrites,
  * interrupted-export remnant refusal) and the pinned golden example under
  * `examples/chat-workflow/fix_widget_task/`.
  *
  * The generated source COMPILING under the pinned dotc is proven separately by
  * `raider.cli.workflow.ChatWorkflowCompileGateSpec` (cli test classpath
  * carries the runtime/tools/provider classes the generated launcher names; the
  * dsl module itself depends only on core).
  */
object ChatWorkflowRendererSpec extends ZIOSpecDefault:

  // ----- representative STS fixture (ADR-033 §11.4 reference lifecycle) -----

  val fixture: StsSelection = StsSelection(
    sessionId = "sess-20261014-fix-widget",
    intent = StsTurn(
      "t1",
      StsTurnKind.Intent,
      StsTurnStatus.Completed,
      "Make the failing widget module compile again and keep its checks green"
    ),
    refinements = List(
      StsTurn(
        "t2",
        StsTurnKind.Refinement,
        StsTurnStatus.Completed,
        "Do not touch the public API"
      ),
      StsTurn(
        "t3",
        StsTurnKind.Refinement,
        StsTurnStatus.Completed,
        "Prefer the smallest possible diff"
      )
    ),
    taskParams = List(
      StsParam("targetPath", "src/main/scala/widget/Widget.scala"),
      StsParam(
        "summary",
        "Widget.scala does not compile after the 0.4 refactor"
      )
    ),
    checks = List(
      NamedCheck("compile", List("sbt", "--batch", "compile")),
      NamedCheck("widget-tests", List("sbt", "--batch", "widget/test"))
    ),
    agentStage = StsAgentStage(maxRounds = 6),
    origin = StsOrigin(
      providerKind = "openai-compatible",
      model = "mock-coder",
      baseUrl = Some("http://127.0.0.1:8901/v1")
    )
  )

  val options: RenderOptions = RenderOptions("fix_widget_task")

  private def renderOrThrow(s: StsSelection, o: RenderOptions): RenderedExport =
    ChatWorkflowRenderer.render(s, o) match
      case Right(r)    => r
      case Left(error) => throw new IllegalStateException(error.detail)

  private def assertRefusal(
      r: Either[RaiderError, ?],
      prefix: String
  ): Boolean =
    r match
      case Left(e: RaiderError.InputValidation) =>
        e.detail.startsWith(prefix) && e.code == "RA-INP"
      case _ => false

  // ----- golden example plumbing (committed under examples/chat-workflow) ----

  private def repoRoot(): java.nio.file.Path =
    val cwd = java.nio.file.Path.of("").toAbsolutePath
    Iterator
      .iterate(Option(cwd))(opt => opt.flatMap(p => Option(p.getParent)))
      .takeWhile(_.isDefined)
      .collect { case Some(dir) => dir }
      .find(dir => Files.isRegularFile(dir.resolve("build.sbt")))
      .getOrElse(throw new IllegalStateException("no build.sbt ancestor"))

  private def readExample(file: String): String =
    Files.readString(
      repoRoot()
        .resolve("examples")
        .resolve("chat-workflow")
        .resolve("fix_widget_task")
        .resolve(file),
      StandardCharsets.UTF_8
    )

  /** RAI-151 invariant: `\xHH` is lexically inert ONLY inside a `//` comment.
    * Every `\x` occurrence in the generated source must sit after the `//` of
    * its line.
    */
  private def commentOnlyEscapeViolations(src: String): Vector[String] =
    src.linesIterator.zipWithIndex.flatMap { (line, i) =>
      if !line.contains("\\x") then None
      else
        val escapeIdx = line.indexOf("\\x")
        val commentIdx = line.indexOf("//")
        if commentIdx < 0 || escapeIdx < commentIdx then
          Some("line " + (i + 1) + ": \\x outside a // comment: " + line)
        else None
    }.toVector

  /** Hostile fixture: quotes, backslashes, newlines, U+0001/DEL, and a shell
    * injection attempt — all as pure DATA (ADR-033 §8: text is data, never a
    * shell instruction).
    */
  val hostile: StsSelection = fixture.copy(
    sessionId = "sess-\u0001-hostile\"quote",
    intent = fixture.intent.copy(
      turnId = "t\u0001i\"nl",
      text = "He said \"run `rm -rf /tmp/x` now\"\nand do it \u0001 FAST\\"
    ),
    refinements = List(
      StsTurn(
        "t2",
        StsTurnKind.Refinement,
        StsTurnStatus.Completed,
        "line1\r\ntab\there\u007fEND"
      )
    ),
    taskParams = List(
      StsParam("targetPath", "pa\"th\u0002one"),
      StsParam("summary", "sum; $(rm -rf /) `whoami` \\ end")
    ),
    checks = List(
      // NOTE: control characters in check ARGV are a legitimate R1 refusal
      // (argv must be printable tokens — ProcessBuilder cannot carry them),
      // so the hostile payload here lives in the check NAME and the quote/
      // backslash DATA of argv, not in argv control bytes.
      NamedCheck("check\u0003one", List("echo", "a\"b\\c"))
    ),
    origin = StsOrigin(
      providerKind = "anthropic-compatible",
      model = "mo\u0005del\"x",
      baseUrl = Some("http://127.0.0.1:1/v\u00061")
    )
  )

  val spec = suite("CWF-C renderer (RAI-155.c, ADR-033 §4/§6/§8)")(
    test(
      "representative STS renders the §6.2 artifact set with Option-L shape"
    ) {
      val r = renderOrThrow(fixture, options)
      val src = r.source
      assertTrue(
        r.name == "fix_widget_task",
        src.contains("object Fix_widget_task:"),
        src.contains("@main def fix_widget_task(args: String*): Unit ="),
        src.contains(
          "final case class Input(schemaVersion: Int, workspace: String, task: TaskParams)"
        ),
        src.contains(
          "final case class TaskParams(targetPath: String, summary: String)"
        ),
        src.contains("input.schemaVersion == 1"),
        src.contains("val maxRounds: Int = 6"),
        src.contains(
          "val checkCompile: List[String] = List(\"sbt\", \"--batch\", \"compile\")"
        ),
        src.contains(
          "val checkWidget_tests: List[String] = List(\"sbt\", \"--batch\", \"widget/test\")"
        ),
        src.contains("val trustedChecks: List[(String, List[String])] = List("),
        src.contains("(\"compile\", checkCompile),"),
        src.contains("(\"widget-tests\", checkWidget_tests),"),
        src.contains("Make the failing widget module compile again"),
        src.contains("Refinement: Do not touch the public API"),
        src.contains("{workspace}"),
        src.contains("{targetPath}"),
        src.contains("{task}"),
        src.contains("def workflow(launch: Launch): Program[String, String] ="),
        src.contains(
          "Program.fromFunction(\"fix_widget_task-workflow\"): rawInput =>"
        ),
        src.contains("decodeOrRefuse(rawInput)"),
        src.contains("agentStage(launch, prompt)"),
        src.contains("runAllChecks(input)"),
        src.contains("AgentLoop.newRoot()"),
        src.contains("loop.runText("),
        src.contains(
          "def runLauncher(args: Seq[String]): ZIO[Any, Nothing, Int] ="
        ),
        src.contains("raider.cli.run.Credentials.resolve(credentialProvider)"),
        src.contains("BudgetLimits.make(maxAttempts = maxRounds)"),
        src.contains("Admission.make(Right(limits))"),
        src.contains("CostEstimate.Unknown"),
        src.contains("AgentLoop.InternalMarker"),
        src.contains("\"openai-compatible\""),
        src.contains("http://127.0.0.1:8901/v1"),
        src.contains("LauncherTemplate.ExplicitAssembly"),
        r.manifestJson.contains("\"kind\": \"chat-workflow-export\""),
        r.manifestJson.contains(
          "\"specDigest\": \"sha256-" + r.specDigest + "\""
        ),
        r.manifestJson.contains(
          "\"sourceDigest\": \"sha256-" + r.sourceDigest + "\""
        ),
        r.manifestJson.contains("\"turnIds\": [\"t1\", \"t2\", \"t3\"]"),
        r.inputExampleJson.contains("\"schemaVersion\": 1"),
        r.inputExampleJson.contains(
          "src/main/scala/widget/Widget.scala"
        ),
        r.inputExampleJson.contains(
          "\"workspace\": \"/absolute/path/to/fresh-workspace-copy\""
        )
      )
    },
    test(
      "determinism: identical selection + options render byte-identical artifacts (§4.3)"
    ) {
      val first = renderOrThrow(fixture, options)
      val second = renderOrThrow(fixture, options)
      assertTrue(
        first.source == second.source,
        first.manifestJson == second.manifestJson,
        first.inputExampleJson == second.inputExampleJson,
        first.specDigest == second.specDigest,
        first.sourceDigest == second.sourceDigest,
        first.source.getBytes(StandardCharsets.UTF_8).toSeq ==
          second.source.getBytes(StandardCharsets.UTF_8).toSeq
      )
    },
    test(
      "different selections render different artifacts (specDigest reflects the STS)"
    ) {
      val other = renderOrThrow(
        fixture.copy(agentStage = StsAgentStage(maxRounds = 7)),
        options
      )
      val base = renderOrThrow(fixture, options)
      assertTrue(
        other.specDigest != base.specDigest,
        other.source.contains("val maxRounds: Int = 7"),
        other.source != base.source
      )
    },
    test(
      "hostile strings: literals carry the \\uXXXX discipline, comments stay inert"
    ) {
      val r = renderOrThrow(hostile, options)
      val src = r.source
      val rawControl = src.exists(c => c < ' ' && c != '\n')
      val violations = commentOnlyEscapeViolations(src)
      // The captured text must appear only as escaped DATA — the raw hostile
      // sequences must never appear verbatim.
      assertTrue(
        !rawControl,
        violations.isEmpty,
        // intent text -> promptTemplate STRING literal (\uXXXX form):
        src.contains("\\\"run `rm -rf /tmp/x` now\\\""),
        src.contains("and do it \\u0001 FAST\\\\"),
        // refinement text -> promptTemplate literal:
        src.contains("line1\\r\\ntab\\there\\u007fEND"),
        // session id / turn ids -> provenance header COMMENT (\xHH form):
        src.contains("sess-\\x01-hostile\\\"quote"),
        src.contains("turns [t\\x01i\\\"nl, t2]"),
        // check name with control char -> trustedChecks tuple literal:
        src.contains("(\"check\\u0003one\", checkCheck_one)"),
        // model / baseUrl -> literals:
        src.contains("mo\\u0005del\\\"x"),
        src.contains("http://127.0.0.1:1/v\\u00061"),
        // param values are EXTERNALIZED to the input example (never inlined
        // into the source — ADR-033 §6.5), JSON-escaped there:
        !src.contains("pa\\\"th"),
        !src.contains("$(rm -rf /)"),
        r.inputExampleJson.contains("pa\\\"th\\u0002one"),
        r.inputExampleJson.contains("sum; $(rm -rf /) `whoami` \\\\ end"),
        // manifest is JSON with proper encoding; raw controls never appear
        !r.manifestJson.exists(c => c < ' ' && c != '\n' && c != '\t'),
        r.manifestJson.contains("\\u0001"),
        r.manifestJson.contains("sess-\\u0001-hostile\\\"quote")
      )
    },
    test(
      "R1: unsupported STS shapes are typed refusals naming the supported shape"
    ) {
      val missingParam = ChatWorkflowRenderer.render(
        fixture.copy(taskParams = List(StsParam("targetPath", "a"))),
        options
      )
      val extraParam = ChatWorkflowRenderer.render(
        fixture.copy(
          taskParams = fixture.taskParams :+ StsParam("deadline", "soon")
        ),
        options
      )
      val noChecks = ChatWorkflowRenderer.render(
        fixture.copy(checks = Nil),
        options
      )
      val badKind = ChatWorkflowRenderer.render(
        fixture.copy(
          intent = fixture.intent.copy(kind = StsTurnKind.Ordinary)
        ),
        options
      )
      val ordinaryRefinement = ChatWorkflowRenderer.render(
        fixture.copy(
          refinements = List(
            fixture.refinements.head.copy(kind = StsTurnKind.Ordinary)
          )
        ),
        options
      )
      val zeroRounds = ChatWorkflowRenderer.render(
        fixture.copy(agentStage = StsAgentStage(0)),
        options
      )
      val badProvider = ChatWorkflowRenderer.render(
        fixture.copy(origin = fixture.origin.copy(providerKind = "vertex")),
        options
      )
      val emptyArgv = ChatWorkflowRenderer.render(
        fixture.copy(checks = List(NamedCheck("x", Nil))),
        options
      )
      val emptyIntent = ChatWorkflowRenderer.render(
        fixture.copy(intent = fixture.intent.copy(text = "  ")),
        options
      )
      val badName = ChatWorkflowRenderer.render(fixture, RenderOptions("match"))
      assertTrue(
        assertRefusal(missingParam, "CWF-R1:"),
        missingParam.left.toOption.get.detail.contains("targetPath"),
        assertRefusal(extraParam, "CWF-R1:"),
        extraParam.left.toOption.get.detail.contains("deadline"),
        assertRefusal(noChecks, "CWF-R1:"),
        assertRefusal(badKind, "CWF-R1:"),
        assertRefusal(ordinaryRefinement, "CWF-R1:"),
        assertRefusal(zeroRounds, "CWF-R1:"),
        assertRefusal(badProvider, "CWF-R1:"),
        assertRefusal(emptyArgv, "CWF-R1:"),
        assertRefusal(emptyIntent, "CWF-R1:"),
        assertRefusal(badName, "CWF-R1:")
      )
    },
    test(
      "R3: non-terminal turns are refused with the offending turn ids (§5.4)"
    ) {
      val interrupted = ChatWorkflowRenderer.render(
        fixture.copy(
          refinements = List(
            fixture.refinements.head,
            fixture.refinements(1).copy(status = StsTurnStatus.Interrupted)
          )
        ),
        options
      )
      val unknownIntent = ChatWorkflowRenderer.render(
        fixture.copy(
          intent = fixture.intent.copy(status = StsTurnStatus.Unknown)
        ),
        options
      )
      assertTrue(
        assertRefusal(interrupted, "CWF-R3:"),
        interrupted.left.toOption.get.detail.contains("t3"),
        interrupted.left.toOption.get.detail.contains("Interrupted"),
        assertRefusal(unknownIntent, "CWF-R3:"),
        unknownIntent.left.toOption.get.detail.contains("t1")
      )
    },
    test(
      "R4: secret-bearing selections are refused and the value is never echoed"
    ) {
      val secret = "sk-abcdefghijklmnopqrstuvwxyz012345"
      val leak = ChatWorkflowRenderer.render(
        fixture.copy(
          refinements = List(
            fixture.refinements.head
              .copy(text = "use key " + secret + " for the stand")
          )
        ),
        options
      )
      val assignment = ChatWorkflowRenderer.render(
        fixture.copy(
          taskParams = List(
            StsParam("targetPath", "a/b"),
            StsParam("summary", "config api_key=supersecretvalue")
          )
        ),
        options
      )
      val detail = leak.left.toOption.get.detail
      assertTrue(
        assertRefusal(leak, "CWF-R4:"),
        !detail.contains(secret),
        detail.contains("refinement text"),
        assertRefusal(assignment, "CWF-R4:"),
        !assignment.left.toOption.get.detail.contains("supersecretvalue")
      )
    },
    test(
      "golden example: renderer output matches the committed examples/chat-workflow files"
    ) {
      val r = renderOrThrow(fixture, options)
      assertTrue(
        readExample("fix_widget_task.scala") == r.source,
        readExample("fix_widget_task-manifest.json") == r.manifestJson,
        readExample("fix_widget_task.input.json") == r.inputExampleJson
      )
    },
    test("exportTo writes the §6.2 layout atomically and never overwrites") {
      val tmp = Files.createTempDirectory("cwf-export")
      val target = tmp.resolve("fix_widget_task")
      val rendered = ChatWorkflowExport.exportTo(fixture, options, target)
      val res = rendered match
        case Right(value) => value
        case Left(error)  => throw new IllegalStateException(error.detail)
      val again = ChatWorkflowExport.exportTo(fixture, options, target)
      val r5 = assertRefusal(again, "CWF-R5:")
      val filesOk = List(
        "fix_widget_task.scala",
        "fix_widget_task-manifest.json",
        "fix_widget_task.input.json"
      ).forall(f => Files.isRegularFile(target.resolve(f)))
      val dirOk = Files.isDirectory(target)
      val stagingGone = !Files.exists(
        tmp.resolve(".fix_widget_task" + ChatWorkflowExport.StagingSuffix)
      )
      val digestsStable =
        res.sourceDigest == renderOrThrow(fixture, options).sourceDigest
      val cleanupOk =
        try
          Files
            .walk(tmp)
            .sorted(java.util.Comparator.reverseOrder[java.nio.file.Path]())
            .forEach(p => Files.deleteIfExists(p))
          true
        catch
          case NonFatal(_) => false
      // All FS-derived facts are captured into vals BEFORE the cleanup ran:
      // the assertTrue macro re-evaluates its expressions lazily, so inline
      // FS calls here would observe the deleted tree.
      assertTrue(
        filesOk,
        dirOk,
        stagingGone,
        r5,
        res.files.size == 3,
        digestsStable,
        cleanupOk
      )
    },
    test(
      "exportTo: existing target, leftover staging and missing parent are typed R5 refusals"
    ) {
      val tmp = Files.createTempDirectory("cwf-export-r5")
      val target = tmp.resolve("x")
      Files.createDirectory(target)
      val existing = ChatWorkflowExport.exportTo(fixture, options, target)
      val symlinkTarget = tmp.resolve("sym")
      Files.createSymbolicLink(symlinkTarget, tmp)
      val symlinked =
        ChatWorkflowExport.exportTo(fixture, options, symlinkTarget)
      val orphanStaging = tmp.resolve("y")
      Files.createDirectories(orphanStaging.getParent)
      Files.createDirectory(
        orphanStaging.getParent.resolve(".y" + ChatWorkflowExport.StagingSuffix)
      )
      val remnant = ChatWorkflowExport.exportTo(fixture, options, orphanStaging)
      val noParent = ChatWorkflowExport.exportTo(
        fixture,
        options,
        tmp.resolve("no-such-parent").resolve("z")
      )
      val results = assertTrue(
        assertRefusal(existing, "CWF-R5:"),
        existing.left.toOption.get.detail.contains("never overwrites"),
        assertRefusal(symlinked, "CWF-R5:"),
        assertRefusal(remnant, "CWF-R5:"),
        remnant.left.toOption.get.detail.contains("interrupted export remnant"),
        assertRefusal(noParent, "CWF-R5:")
      )
      val cleanupOk =
        try
          Files
            .walk(tmp)
            .sorted(java.util.Comparator.reverseOrder[java.nio.file.Path]())
            .forEach(p =>
              if Files.isSymbolicLink(p) || Files.isRegularFile(p) then
                Files.deleteIfExists(p)
              else Files.deleteIfExists(p)
            )
          true
        catch case NonFatal(_) => false
      results && assertTrue(cleanupOk)
    }
  )
