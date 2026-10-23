package raider.dsl.workflow

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

import scala.collection.mutable.ArrayBuffer

import raider.core.RaiderError

/** Launch-construction SEAM of the generated launcher (ADR-033 §6.3/§7).
  *
  * Option L is DECIDED (the export is ordinary editable Scala with an explicit
  * `main`). Which route builds the ADR-029 §3.1 launch capability is
  * RAI-155.d's decision: ADR-029 PKT-1 `WorkflowLaunch.make` vs a CLI-internal
  * assembly over the same runtime parts under identical D1/D3/D4 rules. The
  * renderer therefore parameterizes the launcher section and never hard-freezes
  * it; a later slice adds its case here without changing the render contract.
  */
enum LauncherTemplate:

  /** Built-in template: the generated `main` assembles the launch services
    * EXPLICITLY from public runtime parts (credentials from env/helper
    * references with typed auth failure and no hidden fallback, coding tools
    * rooted at the input workspace, validated limits, one Admission/ledger per
    * run). Compiles against the product classpath today; exercised by the
    * pinned-dotc compile gate (CWF-C01).
    */
  case ExplicitAssembly

  /** Stable identifier recorded in the manifest so consumers (RAI-155.d/.e) can
    * tell which launch shape a source file carries.
    */
  def manifestName: String = this match
    case ExplicitAssembly => "explicit-assembly"

end LauncherTemplate

/** Render options: the export name (also the on-disk artifact name; slugged
  * deterministically) and the launch-construction template.
  */
final case class RenderOptions(
    name: String,
    launcher: LauncherTemplate = LauncherTemplate.ExplicitAssembly
)

/** The three deterministic export artifacts of ADR-033 §6.2 plus the identity
  * digests. All fields are plain text; `exportTo` (ChatWorkflowExport) owns the
  * file-write discipline.
  */
final case class RenderedExport(
    name: String,
    source: String,
    manifestJson: String,
    inputExampleJson: String,
    specDigest: String,
    sourceDigest: String
)

/** Deterministic workflow GENERATOR for the Supported Task Specification
  * (ADR-033 §4/§6, RAI-155.c).
  *
  * `render` is a PURE function: identical selection + options -> byte-identical
  * artifacts (no clock, no randomness, no model call — ADR-033 §4.3). It
  * performs no I/O and dispatches nothing agent-shaped (CWF-C02/A02).
  *
  * Refusals are typed [[RaiderError.InputValidation]] values carrying the
  * structured `CWF-Rn` detail prefix of ADR-033 §8 (R1 unsupported shape, R3
  * non-terminal turns, R4 secret-bearing selection). Secret values never appear
  * in a refusal detail.
  */
object ChatWorkflowRenderer:

  /** Non-secret generator stamp for the manifest (ADR-033 §6.4). Kept in sync
    * with the build version; a bump updates the golden example test.
    */
  val GeneratorId: String = "raider 0.1.0-SNAPSHOT"

  /** Input-schema version of the externalized parameters (ADR-033 §6.6). */
  val InputSchemaVersion: Int = 1

  /** Manifest schema version (ADR-033 §6.4). */
  val ManifestSchemaVersion: Int = 1

  /** The first slice's named task parameters (ADR-033 §4.1): exactly these,
    * externalized to the input file, never hard-coded into the source.
    */
  val RequiredParamNames: List[String] = List("targetPath", "summary")

  /** Placeholder substitutions supported by the generated `renderPrompt`. */
  val Placeholders: List[String] = List("workspace", "targetPath", "task")

  /** First-slice provider kinds (ADR-033 §6.4 origin). The generated launcher
    * binds the two first-class transports explicitly; there is no hidden
    * fallback (ADR-029 H7).
    */
  val SupportedProviderKinds: List[String] = List(
    "openai-compatible",
    "anthropic-compatible"
  )

  /** Renders the three export artifacts. Deterministic and total over valid STS
    * selections; malformed selections are typed refusals.
    */
  def render(
      selection: StsSelection,
      options: RenderOptions
  ): Either[RaiderError, RenderedExport] =
    for
      name <- validName(options.name)
      _ <- validate(selection)
      specDigest = sha256Hex(canonicalText(selection))
      source = renderSource(selection, options, name)
      sourceDigest = sha256Hex(source)
      manifest = renderManifest(selection, options, specDigest, sourceDigest)
      inputExample = renderInputExample(selection)
    yield RenderedExport(
      name = name,
      source = source,
      manifestJson = manifest,
      inputExampleJson = inputExample,
      specDigest = specDigest,
      sourceDigest = sourceDigest
    )

  // ------------------------------------------------------------------//
  // Validation (ADR-033 §8 R1/R3/R4)                                   //
  // ------------------------------------------------------------------//

  private def validName(raw: String): Either[RaiderError, String] =
    val slug = slugify(raw)
    if slug.isEmpty then
      Left(
        refuse(
          "R1",
          "export name is empty after sanitization; supply a name with letters/digits/underscore"
        )
      )
    else if ReservedWords.contains(slug) then
      Left(
        refuse(
          "R1",
          "export name '" + slug + "' is a Scala reserved word and would not compile"
        )
      )
    else Right(slug)

  /** Typed refusal with the ADR-033 §8 structured detail prefix. */
  private def refuse(code: String, what: String): RaiderError =
    RaiderError.InputValidation("CWF-" + code + ": " + what)

  private def validate(s: StsSelection): Either[RaiderError, Unit] =
    for
      _ <- requireNonBlank("session id", s.sessionId)
      _ <- requireNonBlank("intent turn id", s.intent.turnId)
      _ <- requireNonBlank("intent text", s.intent.text)
      _ <- completed("intent", s.intent)
      _ <- requireKind(s.intent)
      _ <- validateRefinements(s.refinements)
      _ <- validateParams(s.taskParams)
      _ <- validateChecks(s.checks)
      _ <- validateStage(s.agentStage)
      _ <- validateOrigin(s.origin)
      _ <- scanSecrets(s)
    yield ()

  private def requireNonBlank(
      what: String,
      value: String
  ): Either[RaiderError, Unit] =
    if value.trim.isEmpty then
      Left(
        refuse(
          "R1",
          what + " must be non-empty; the Supported Task Specification requires explicit non-empty content (ADR-033 §4.1)"
        )
      )
    else Right(())

  private def completed(what: String, t: StsTurn): Either[RaiderError, Unit] =
    t.status match
      case StsTurnStatus.Completed => Right(())
      case other =>
        Left(
          refuse(
            "R3",
            what + " turn '" + t.turnId + "' is " + other +
              "; selection contains non-terminal turns and export cannot proceed (ADR-033 §5.4/§8 R3); re-run or narrow the selection"
          )
        )

  private def requireKind(intent: StsTurn): Either[RaiderError, Unit] =
    if intent.kind == StsTurnKind.Intent then Right(())
    else
      Left(
        refuse(
          "R1",
          "turn '" + intent.turnId + "' is kind " + intent.kind +
            "; the intent must be an explicitly marked Intent turn (ADR-033 §4.1)"
        )
      )

  private def validateRefinements(
      refinements: List[StsTurn]
  ): Either[RaiderError, Unit] =
    refinements.foldLeft[Either[RaiderError, Unit]](Right(())) { (acc, r) =>
      for
        _ <- acc
        _ <- requireNonBlank("refinement turn id", r.turnId)
        _ <- requireNonBlank("refinement text (turn " + r.turnId + ")", r.text)
        _ <- completed("refinement", r)
        _ <-
          if r.kind == StsTurnKind.Refinement then Right(())
          else
            Left(
              refuse(
                "R1",
                "turn '" + r.turnId + "' is kind " + r.kind +
                  "; refinements must be explicitly marked Refinement turns in selection order (ADR-033 §4.1)"
              )
            )
      yield ()
    }

  private def validateParams(
      params: List[StsParam]
  ): Either[RaiderError, Unit] =
    val names = params.map(_.name)
    val missing = RequiredParamNames.filterNot(names.contains)
    val unknown = names.filterNot(RequiredParamNames.contains)
    val dup = names.diff(names.distinct)
    if missing.nonEmpty then
      Left(
        refuse(
          "R1",
          "task parameters missing: " + missing.mkString(", ") +
            "; the first slice supports exactly the named params " +
            RequiredParamNames.mkString(", ") +
            " (ADR-033 §4.1/§6.6); unsupported selections are refused, never approximated"
        )
      )
    else if unknown.nonEmpty then
      Left(
        refuse(
          "R1",
          "unsupported task parameters: " + unknown.mkString(", ") +
            "; the first slice supports exactly " +
            RequiredParamNames.mkString(", ") + " (ADR-033 §4.1/§6.6)"
        )
      )
    else if dup.nonEmpty then
      Left(
        refuse("R1", "duplicate task parameters: " + dup.mkString(", "))
      )
    else
      params.foldLeft[Either[RaiderError, Unit]](Right(())) { (acc, p) =>
        for
          _ <- acc
          _ <- requireNonBlank("task param '" + p.name + "'", p.value)
        yield ()
      }

  private def validateChecks(
      checks: List[NamedCheck]
  ): Either[RaiderError, Unit] =
    if checks.isEmpty then
      Left(
        refuse(
          "R1",
          "no trusted checks; the STS requires at least one user-registered named check (ADR-033 §4.1 SuccessPolicy)"
        )
      )
    else
      checks.foldLeft[Either[RaiderError, Unit]](Right(())) { (acc, c) =>
        for
          _ <- acc
          _ <- requireNonBlank("check name", c.name)
          _ <-
            if c.argv.isEmpty then
              Left(
                refuse(
                  "R1",
                  "check '" + c.name + "' has an empty argv; checks are validated argv lists (ADR-033 §4.1)"
                )
              )
            else
              c.argv.foldLeft[Either[RaiderError, Unit]](Right(())) {
                (acc2, arg) =>
                  for
                    _ <- acc2
                    _ <- requireNonBlank(
                      "check '" + c.name + "' argv element",
                      arg
                    )
                    _ <-
                      if arg.exists(isControlChar) then
                        Left(
                          refuse(
                            "R1",
                            "check '" + c.name +
                              "' argv element contains a control character; argv must be plain printable tokens"
                          )
                        )
                      else Right(())
                  yield ()
              }
          _ <-
            if slugify(c.name).isEmpty then
              Left(
                refuse(
                  "R1",
                  "check name '" + c.name + "' has no usable identifier form"
                )
              )
            else Right(())
        yield ()
      }

  private def validateStage(stage: StsAgentStage): Either[RaiderError, Unit] =
    if stage.maxRounds < 1 then
      Left(
        refuse(
          "R1",
          "agent stage maxRounds must be >= 1, got " + stage.maxRounds +
            "; the stage is bounded work (ADR-033 §4.1)"
        )
      )
    else Right(())

  private def validateOrigin(o: StsOrigin): Either[RaiderError, Unit] =
    for
      _ <-
        if SupportedProviderKinds.contains(o.providerKind) then Right(())
        else
          Left(
            refuse(
              "R1",
              "unsupported providerKind '" + o.providerKind +
                "'; supported: " + SupportedProviderKinds.mkString(", ") +
                " (the launcher binds the two first-class transports explicitly; no hidden fallback)"
            )
          )
      _ <- requireNonBlank("model name", o.model)
      _ <- o.baseUrl match
        case None => Right(())
        case Some(url) =>
          if url.startsWith("http://") || url.startsWith("https://") then
            Right(())
          else
            Left(
              refuse(
                "R1",
                "origin baseUrl must be an http(s) URL reference, got a non-URL value"
              )
            )
    yield ()

  // ------------------------------------------------------------------//
  // R4: secret scan (ADR-033 §6.5/§8) — refuse, never echo the value.   //
  // ------------------------------------------------------------------//

  // Conservative by design: a false refusal costs a re-wording, a false pass
  // would write a credential into the export. Long-hex may hit benign
  // digests in prose; that is accepted for the first slice.
  private val SecretPatterns: List[(String, scala.util.matching.Regex)] = List(
    "OpenAI-style key (sk-...)" -> raw"sk-[A-Za-z0-9_-]{16,}".r,
    "AWS access key id (AKIA...)" -> raw"AKIA[0-9A-Z]{16}".r,
    "GitHub token" -> raw"gh[pousr]_[A-Za-z0-9]{20,}".r,
    "Google API key" -> raw"AIza[0-9A-Za-z_\-]{32,}".r,
    "Slack token" -> raw"xox[baprs]-[A-Za-z0-9\-]{10,}".r,
    "private key block" -> raw"-----BEGIN [A-Z ]*PRIVATE KEY-----".r,
    "credential assignment" -> raw"(?i)(api[_-]?key|secret|passwd|password|token|authorization)\s*[=:]\s*\S{8,}".r,
    "long hex secret (32+ hex digits)" -> raw"\b[0-9a-fA-F]{32,64}\b".r
  )

  private def scanSecrets(s: StsSelection): Either[RaiderError, Unit] =
    val fields: List[(String, String)] =
      List(
        ("session id", s.sessionId),
        ("intent turn id", s.intent.turnId),
        ("intent text", s.intent.text)
      ) ++
        s.refinements.flatMap(r =>
          List(
            ("refinement turn id", r.turnId),
            ("refinement text (turn " + r.turnId + ")", r.text)
          )
        ) ++
        s.taskParams.map(p => ("task param '" + p.name + "'", p.value)) ++
        s.checks.flatMap(c =>
          ("check name '" + c.name + "'", c.name) ::
            c.argv.map(a => ("check '" + c.name + "' argv element", a))
        ) ++
        List(
          ("model name", s.origin.model),
          ("provider kind", s.origin.providerKind)
        ) ++
        s.origin.baseUrl.map(u => ("origin base URL", u)).toList
    fields.foldLeft[Either[RaiderError, Unit]](Right(())) { (acc, field) =>
      acc.flatMap { _ =>
        SecretPatterns.foldLeft[Either[RaiderError, Unit]](Right(())) {
          (acc2, pat) =>
            acc2.flatMap { _ =>
              if pat._2.findFirstIn(field._2).isDefined then
                Left(
                  refuse(
                    "R4",
                    "secret-shaped value detected in " + field._1 +
                      " (" + pat._1 + "); export refused and the value is not echoed (ADR-033 §6.5/§8 R4)"
                  )
                )
              else Right(())
            }
        }
      }
    }

  private def isControlChar(c: Char): Boolean = c < ' ' || c == '\u007f'

  // ------------------------------------------------------------------//
  // Escape discipline (RAI-151 precedents, LineageProjection)           //
  // ------------------------------------------------------------------//

  /** Identifier slug: letters/digits/underscore kept, everything else mapped to
    * `_`; empty or digit-leading results are prefixed so the result is a valid
    * Scala identifier. Deterministic (same discipline as the ADR-031 projection
    * renderer).
    */
  private def slugify(raw: String): String =
    val cleaned = raw.map(c => if c.isLetterOrDigit || c == '_' then c else '_')
    if cleaned.isEmpty || cleaned.head.isDigit then "_" + cleaned
    else cleaned

  /** Escape discipline for free text entering a generated STRING literal: `\`
    * and `"` escaped classically, `\n`/`\r`/`\t` keep their single-char
    * escapes, and every other C0 control or DEL becomes `\uXXXX` (backslash,
    * `u`, four LOWERCASE hex digits). The pinned dotc 3.9.0 rejects `\xHH`
    * inside string literals, so the canonical form must never enter one; the
    * quote/backslash branches run first so `\u0022`/`\u005c` are never emitted.
    * Deterministic and injective by construction.
    */
  private def escapeLiteral(raw: String): String =
    val out = new StringBuilder(raw.length + 8)
    raw.foreach {
      case '\\'                          => out.append("\\\\")
      case '"'                           => out.append("\\\"")
      case '\n'                          => out.append("\\n")
      case '\r'                          => out.append("\\r")
      case '\t'                          => out.append("\\t")
      case c if c < ' ' || c == '\u007f' => out.append(f"\\u${c.toInt}%04x")
      case c                             => out.append(c)
    }
    out.result()

  /** Escape discipline for free text entering a `//` COMMENT of the generated
    * source: control characters become the lexically-inert `\xHH` form
    * (lowercase hex), quotes/backslashes are escaped so no comment line can be
    * split or closed by a hostile payload. `\xHH` must never enter a string
    * literal — only this comment context uses it.
    */
  private def escapeComment(raw: String): String =
    val out = new StringBuilder(raw.length + 8)
    raw.foreach {
      case '\\'                          => out.append("\\\\")
      case '"'                           => out.append("\\\"")
      case '\n'                          => out.append("\\n")
      case '\r'                          => out.append("\\r")
      case '\t'                          => out.append("\\t")
      case c if c < ' ' || c == '\u007f' => out.append(f"\\x${c.toInt}%02x")
      case c                             => out.append(c)
    }
    out.result()

  /** Escape discipline for JSON strings (manifest / input example). */
  private def jsonStr(raw: String): String =
    val out = new StringBuilder(raw.length + 8)
    out.append('"')
    raw.foreach {
      case '\\'                          => out.append("\\\\")
      case '"'                           => out.append("\\\"")
      case '\n'                          => out.append("\\n")
      case '\r'                          => out.append("\\r")
      case '\t'                          => out.append("\\t")
      case c if c < ' ' || c == '\u007f' => out.append(f"\\u${c.toInt}%04x")
      case c                             => out.append(c)
    }
    out.append('"')
    out.result()

  // ------------------------------------------------------------------//
  // Identity digests                                                    //
  // ------------------------------------------------------------------//

  /** Canonical, deterministic serialization of the selection for the
    * `specDigest` (ADR-033 §6.4). Field order is fixed; values are not escaped
    * (this text is hashed, never rendered).
    */
  private def canonicalText(s: StsSelection): String =
    val sb = new StringBuilder
    sb.append("raider-sts/1\n")
    sb.append("session=").append(s.sessionId).append('\n')
    sb.append("intent=").append(turnLine(s.intent)).append('\n')
    s.refinements.zipWithIndex.foreach { (r, i) =>
      sb.append("refinement[")
        .append(i)
        .append("]=")
        .append(turnLine(r))
        .append('\n')
    }
    RequiredParamNames.foreach { name =>
      val p = s.taskParams.find(_.name == name)
      sb.append("param ")
        .append(name)
        .append('=')
        .append(p.map(_.value).getOrElse(""))
        .append('\n')
    }
    sb.append("agent.maxRounds=").append(s.agentStage.maxRounds).append('\n')
    s.checks.foreach { c =>
      sb.append("check ")
        .append(c.name)
        .append('=')
        .append(c.argv.mkString(" "))
        .append(':')
        .append(c.advisory)
        .append('\n')
    }
    sb.append("origin.providerKind=").append(s.origin.providerKind).append('\n')
    sb.append("origin.model=").append(s.origin.model).append('\n')
    sb.append("origin.baseUrl=")
      .append(s.origin.baseUrl.getOrElse("-"))
      .append('\n')
    sb.result()

  // Known limitation: "|" is unescaped here, so turn text containing "|" makes this line ambiguous — specDigest is provenance identity only (hashed, never executed); escaping deferred to a separate contract change.
  private def turnLine(t: StsTurn): String =
    t.turnId + "|" + t.kind + "|" + t.status + "|" + t.text

  private def sha256Hex(text: String): String =
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8))
    bytes.map(b => f"${b & 0xff}%02x").mkString

  // ------------------------------------------------------------------//
  // Artifact rendering                                                  //
  // ------------------------------------------------------------------//

  private def renderSource(
      s: StsSelection,
      options: RenderOptions,
      name: String
  ): String =
    // Naming contract (RAI-158; RAI-155.d finding 1): the top-level
    // definitions must be pairwise distinct CASE-INSENSITIVELY. dotc emits
    // `object <objectName>` as <objectName>.class + <objectName>$.class and
    // `@main def <launcherMain>` as <launcherMain>.class; a case-mirror pair
    // overwrites itself on case-insensitive filesystems (macOS default) and
    // the write order decides which class survives. The OBJECT therefore
    // keeps `<Capitalized name>` — the CLI run path
    // (WorkflowBuildIdentity.deriveClassNames) derives the module class
    // `<Capitalized stem>$` from the source-file stem and loads the generated
    // object reflectively by that name — while the @main launcher takes the
    // `run` prefix: `run<Capitalized name>` differs from every other emitted
    // class for ANY slug (the prefix adds length, so equality ignoring case
    // is impossible).
    val objectName = capitalize(name)
    val launcherMain = "run" + capitalize(name)
    val out = ArrayBuffer.empty[String]

    // ---- provenance / discipline header (comment-escaped) ----
    out += "// Generated by Raider chat export (RAI-155 chain, ADR-033 §6.3) — EDITABLE SOURCE."
    out += "// Provenance: session " + escapeComment(
      s.sessionId
    ) + ", turns [" +
      escapeComment(
        (s.intent :: s.refinements).map(_.turnId).mkString(", ")
      ) + "]; identity in " + escapeComment(name + "-manifest.json") + "."
    out += "// This file is trusted Scala: it runs with this process's permissions, and tool"
    out += "// policy is not a sandbox (runtime-contracts §10). The chat text rendered below"
    out += "// is DATA captured from a chat session — it is never an instruction to invoke a"
    out += "// shell. The only executed commands are the named trusted checks defined here"
    out += "// (validated argv, no shell) and the agent stage's policy-governed tool calls"
    out += "// at RUN time (ADR-033 §8)."
    out += "//"
    out += "// Launch construction (SEAM): the launcher section below is rendered from the"
    out += "// built-in LauncherTemplate." + options.launcher.toString +
      " — an explicit assembly over"
    out += "// public runtime parts honoring ADR-029 §3.1 rules D1/D3/D4. The PKT-1-capability"
    out += "// vs CLI-internal route decision belongs to RAI-155.d (ADR-033 §6.3/§7): the"
    out += "// renderer parameterizes this section; a different launcher template replaces"
    out += "// only the launcher part. Construction dispatches nothing; credentials come"
    out += "// from env/helper references (RAIDER_API_KEY / RAIDER_API_KEY_CMD) and never"
    out += "// appear in this file, the manifest or logs. Cost honesty (ADR-033 K5): usage"
    out += "// on this path stays unpriced/unknown, never zero."
    out += ""
    out += "import raider.core.{ModelBackend, Program, RaiderError, RequestMessage, Task, ToolRegistry}"
    out += "import raider.runtime.admission.Admission"
    out += "import raider.runtime.budget.{BudgetLimits, CostEstimate}"
    out += "import raider.runtime.loop.AgentLoop"
    out += "import raider.tools.CodingToolset"
    out += "import zio.ZIO"
    out += "import zio.json.*"
    out += ""
    out += "object " + objectName + ":"
    out += ""
    out += "  /** Input boundary (ADR-033 §6.6): validated JSON decoding INSIDE the String"
    out += "  * boundary. `workspace` is the per-run fresh copy; the nested task params"
    out += "  * are the externalized task parameters read at RUN time — a second"
    out += "  * task/workspace input needs no edit and no regeneration."
    out += "  */"
    out += "  final case class TaskParams(targetPath: String, summary: String)"
    out += ""
    out += "  object TaskParams:"
    out += "    given zio.json.JsonCodec[TaskParams] = zio.json.DeriveJsonCodec.gen"
    out += ""
    out += "  final case class Input(schemaVersion: Int, workspace: String, task: TaskParams)"
    out += ""
    out += "  object Input:"
    out += "    given zio.json.JsonCodec[Input] = zio.json.DeriveJsonCodec.gen"
    out += ""
    out += "  // ---- editable parameters (the meaningful-edit surface, ADR-033 §6.5) ----"
    out += "  // EDIT THESE CONSTANTS to change behavior WITHOUT regenerating; recompile"
    out += "  // only. The prompt template carries {workspace} {targetPath} {task}"
    out += "  // placeholders substituted from the input at run time."

    // ---- prompt template (intent + ordered refinements) ----
    val prompt = buildPromptTemplate(s)
    out += ""
    out += "  val promptTemplate: String ="
    out += "    \"" + escapeLiteral(prompt) + "\""

    // ---- named trusted checks ----
    out += ""
    out += "  // Named trusted checks (ADR-033 §4.1): validated argv, executed WITHOUT a"
    out += "  // shell in the workspace. Model text can never add or alter a check —"
    out += "  // checks exist only here; the input JSON can never inject one."
    s.checks.foreach { c =>
      out += ""
      out += "  val check" + capitalize(slugify(c.name)) +
        ": List[String] = List(" +
        c.argv.map(a => "\"" + escapeLiteral(a) + "\"").mkString(", ") + ")"
    }
    out += ""
    out += "  // advisory is frozen workflow metadata; legacy declarations default false."
    out += "  val trustedChecks: List[(String, List[String], Boolean)] = List("
    s.checks.foreach { c =>
      out += "    (\"" + escapeLiteral(c.name) + "\", check" +
        capitalize(slugify(c.name)) + ", " + c.advisory.toString + "),"
    }
    out += "  )"

    // ---- bounds and provider binding ----
    out += ""
    out += "  // Bound of the ONE sequential agent stage (ADR-033 §4.1); must stay >= 1."
    out += "  val maxRounds: Int = " + s.agentStage.maxRounds
    out += ""
    out += "  // Per-check wall bound in seconds (bounded work; the coding toolset's"
    out += "  // process-tool default)."
    out += "  val checkTimeoutSeconds: Int = 120"
    out += ""
    out += "  // Provider binding (non-secret references; credentials stay env/helper"
    out += "  // references and never appear in this file)."
    out += "  val providerKind: String = \"" + escapeLiteral(
      s.origin.providerKind
    ) + "\""
    out += "  val baseUrl: String = \"" +
      escapeLiteral(s.origin.baseUrl.getOrElse("")) + "\""
    out += ""
    out += "  val modelName: String = \"" + escapeLiteral(s.origin.model) + "\""

    // ---- explicit services ----
    out += ""
    out += "  /** Explicit launch services (ADR-029 §3.1 rule 2): backend, tools,"
    out += "  * admission and limits are constructor arguments, never globals."
    out += "  */"
    out += "  final case class Launch("
    out += "      backend: ModelBackend,"
    out += "      tools: ToolRegistry,"
    out += "      admission: Admission,"
    out += "      limits: BudgetLimits"
    out += "  )"

    // ---- launch construction SEAM (LauncherTemplate.ExplicitAssembly) ----
    out += ""
    out += "  // ---- launch construction SEAM (LauncherTemplate." +
      options.launcher.toString + ") ----"
    out += "  // Explicit assembly over public runtime parts: credentials resolved from"
    out += "  // CURRENT env (typed auth failure, no hidden fallback — ADR-029 H7), coding"
    out += "  // tools rooted at the input workspace, validated limits, ONE Admission and"
    out += "  // ledger for this run (D3: one root/admission/ledger per run; the agent"
    out += "  // stage creates its loop root below). No Runtime.default in this path; the"
    out += "  // first network call happens only when the agent stage runs."
    out += ""
    out += "  private def credentialProvider: String = providerKind match"
    out += "    case \"openai-compatible\"    => \"openai\""
    out += "    case \"anthropic-compatible\" => \"anthropic\""
    out += "    case other                  => other"
    out += ""
    out += "  private def buildBackend(keyOpt: Option[String]): Task[ModelBackend] ="
    out += "    providerKind match"
    out += "      case \"openai-compatible\" =>"
    out += "        keyOpt match"
    out += "          case None =>"
    out += "            Task.fail("
    out += "              RaiderError.ProviderAuth("
    out += "                \"no credential for \" + providerKind +"
    out += "                  \": set RAIDER_API_KEY or RAIDER_API_KEY_CMD\""
    out += "              )"
    out += "            )"
    out += "          case Some(key) =>"
    out += "            Task("
    out += "              raider.provider.chat.stream.OpenAIChatStreamingBackend.make("
    out += "                raider.provider.chat.OpenAIChatBackend.Config.make("
    out += "                  baseUrl,"
    out += "                  key,"
    out += "                  callTimeoutMs = 300000L"
    out += "                )"
    out += "              )"
    out += "            )"
    out += "      case \"anthropic-compatible\" =>"
    out += "        keyOpt match"
    out += "          case None =>"
    out += "            Task.fail("
    out += "              RaiderError.ProviderAuth("
    out += "                \"no credential for \" + providerKind +"
    out += "                  \": set RAIDER_API_KEY or RAIDER_API_KEY_CMD\""
    out += "              )"
    out += "            )"
    out += "          case Some(key) =>"
    out += "            Task("
    out += "              raider.provider.anthropic.AnthropicMessagesBackend.make("
    out += "                raider.provider.anthropic.AnthropicMessagesBackend.Config"
    out += "                  .make(baseUrl, key, callTimeoutMs = 300000L),"
    out += "                streaming = true"
    out += "              )"
    out += "            )"
    out += "      case other =>"
    out += "        Task.fail("
    out += "          RaiderError.Configuration("
    out += "            \"unsupported providerKind: \" + other +"
    out += "              \"; supported: openai-compatible, anthropic-compatible\""
    out += "          )"
    out += "        )"
    out += ""
    out += "  /** Builds the launch services for THIS run (D1/D3): construction"
    out += "  * validates config and allocates admission primitives but performs no"
    out += "  * network call and starts no agent work (D1 rule 1)."
    out += "  */"
    out += "  def buildLaunch(input: Input): Task[Launch] ="
    out += "    Task("
    out += "      ZIO.fromEither("
    out += "        raider.cli.run.Credentials.resolve(credentialProvider)"
    out += "      )"
    out += "    ).flatMap { keyOpt =>"
    out += "      buildBackend(keyOpt).flatMap { backend =>"
    out += "        Task("
    out += "          for"
    out += "            tools <- CodingToolset"
    out += "              .make(input.workspace)"
    out += "              .flatMap(ts => ZIO.fromEither(ts.registry))"
    out += "            limits <- ZIO.fromEither("
    out += "              BudgetLimits.make(maxAttempts = maxRounds)"
    out += "            )"
    out += "            admission <- Admission.make(Right(limits))"
    out += "          yield Launch(backend, tools, admission, limits)"
    out += "        )"
    out += "      }"
    out += "    }"

    // ---- the definition ----
    out += ""
    out += "  // ---- the definition (ADR-033 §6.3) ----"
    out += "  // Building any of the values below dispatches NOTHING (API-03): the"
    out += "  // workflow is a lazy Program[String, String]; only the launcher runs it."
    out += ""
    out += "  /** Validated decoding INSIDE the String boundary (schema-version gate,"
    out += "  * §6.6), then workspace validation (exists, is a directory, not a"
    out += "  * symlink). Typed failure, never a throw across the boundary."
    out += "  */"
    out += "  def decodeOrRefuse(raw: String): Task[Input] ="
    out += "    Task("
    out += "      ZIO"
    out += "        .fromEither(raw.fromJson[Input])"
    out += "        .mapError(err =>"
    out += "          RaiderError.InputValidation("
    out += "            \"CWF-input: invalid input JSON: \" + err"
    out += "          )"
    out += "        )"
    out += "        .flatMap { input =>"
    out += "          if input.schemaVersion == " + InputSchemaVersion + " then ZIO.succeed(input)"
    out += "          else"
    out += "            ZIO.fail("
    out += "              RaiderError.InputValidation("
    out += "                \"CWF-input: unsupported input schemaVersion \" +"
    out += "                  input.schemaVersion + \" (expected " + InputSchemaVersion + ")\""
    out += "              )"
    out += "            )"
    out += "        }"
    out += "    ).flatMap(input => validateWorkspace(input).map(_ => input))"
    out += ""
    out += "  def validateWorkspace(input: Input): Task[Unit] ="
    out += "    Task("
    out += "      ZIO.attemptBlocking {"
    out += "        val p = java.nio.file.Paths.get(input.workspace)"
    out += "        if !p.isAbsolute then Left(\"workspace path must be absolute\")"
    out += "        else if !java.nio.file.Files.isDirectory(p) then"
    out += "          Left(\"workspace must be an existing directory: \" + input.workspace)"
    out += "        else if java.nio.file.Files.isSymbolicLink(p) then"
    out += "          Left(\"workspace must not be a symlink: \" + input.workspace)"
    out += "        else Right(())"
    out += "      }.mapError(t =>"
    out += "        RaiderError.InputValidation("
    out += "          \"CWF-input: workspace validation failed: \" + String.valueOf(t)"
    out += "        )"
    out += "      ).flatMap {"
    out += "        case Right(()) => ZIO.unit"
    out += "        case Left(msg) =>"
    out += "          ZIO.fail(RaiderError.InputValidation(\"CWF-input: \" + msg))"
    out += "      }"
    out += "    )"
    out += ""
    out += "  /** Substitutes the run's externalized parameters into the editable"
    out += "  * template. Purely textual — the input values are DATA and are never"
    out += "  * interpreted as commands (ADR-033 §8)."
    out += "  */"
    out += "  def renderPrompt(template: String, input: Input): String ="
    out += "    template"
    out += "      .replace(\"{workspace}\", input.workspace)"
    out += "      .replace(\"{targetPath}\", input.task.targetPath)"
    out += "      .replace(\"{task}\", input.task.summary)"
    out += ""
    out += "  /** The exported workflow: validated input, ONE bounded sequential"
    out += "  * agent stage over the explicit services, output validation, then the"
    out += "  * named trusted checks on the NEW workspace (a failed check fails the"
    out += "  * run — ci-runtime §7; R9)."
    out += "  */"
    out += "  def workflow(launch: Launch): Program[String, String] ="
    out += "    Program.fromFunction(\"" + escapeLiteral(
      decapitalize(name) + "-workflow"
    ) +
      "\"): rawInput =>"
    out += "      for"
    out += "        input <- decodeOrRefuse(rawInput)"
    out += "        prompt = renderPrompt(promptTemplate, input)"
    out += "        answer <- agentStage(launch, prompt)"
    out += "        validated <- validateOutput(answer)"
    out += "        _ <- runAllChecks(input)"
    out += "      yield report(validated, input)"
    out += ""
    out += "  /** ONE sequential bounded agent stage (ADR-033 §4.1): model+tool"
    out += "  * rounds through the existing AgentLoop over the explicit services;"
    out += "  * bounded by `maxRounds` and re-validated by current limits at run"
    out += "  * time; no parallelism, no delegation, no second scheduler (D3/D4)."
    out += "  */"
    out += "  private def agentStage(launch: Launch, prompt: String): Task[String] ="
    out += "    Task("
    out += "      for"
    out += "        root <- ZIO.succeed(AgentLoop.newRoot())"
    out += "        loop = AgentLoop("
    out += "          backend = launch.backend,"
    out += "          tools = launch.tools,"
    out += "          model = modelName,"
    out += "          admission = launch.admission,"
    out += "          root = root,"
    out += "          attemptEstimate = CostEstimate.Unknown"
    out += "        )"
    out += "        finalText <- loop.runText("
    out += "          List(RequestMessage(\"user\", prompt)),"
    out += "          launch.limits,"
    out += "          maxRounds"
    out += "        )"
    out += "      yield finalText"
    out += "    )"
    out += ""
    out += "  /** Output validation: the recorded chat answer is never replayed —"
    out += "  * the stage's own fresh answer must be non-empty and marker-free."
    out += "  */"
    out += "  private def validateOutput(answer: String): Task[String] ="
    out += "    if answer.trim.isEmpty then"
    out += "      Task.fail("
    out += "        RaiderError.OutputValidation("
    out += "          \"agent stage returned an empty answer\""
    out += "        )"
    out += "      )"
    out += "    else if answer.contains(AgentLoop.InternalMarker) then"
    out += "      Task.fail("
    out += "        RaiderError.OutputValidation("
    out += "          \"agent answer carries the internal marker; refusing to publish\""
    out += "        )"
    out += "      )"
    out += "    else Task.succeed(answer)"
    out += ""
    out += "  /** All named checks in order (ADR-033 §4.1 SuccessPolicy: checks must"
    out += "  * pass on the NEW workspace; \"the agent said done\" is never a"
    out += "  * verdict)."
    out += "  */"
    out += "  private def runAllChecks(input: Input): Task[Unit] ="
    out += "    Task("
    out += "      ZIO.foreach(trustedChecks) { case (name, argv, _) =>"
    out += "        Task.zio(runNamedCheck(input, name, argv))"
    out += "      }.unit"
    out += "    )"
    out += ""
    out += "  /** One named check: argv runs WITHOUT a shell in the workspace"
    out += "  * directory, bounded wait, output inherited. The argv comes ONLY from"
    out += "  * the editable constants above — never from the input JSON"
    out += "  * (text-is-data, §8). Nonzero exit is a typed failed run (R9): a"
    out += "  * failed check stays failed."
    out += "  */"
    out += "  private def runNamedCheck("
    out += "      input: Input,"
    out += "      name: String,"
    out += "      argv: List[String]"
    out += "  ): Task[Unit] ="
    out += "    Task("
    out += "      ZIO"
    out += "        .attemptBlocking {"
    out += "          val proc = new ProcessBuilder(argv*)"
    out += "            .directory(java.nio.file.Paths.get(input.workspace).toFile)"
    out += "            .redirectErrorStream(true)"
    out += "            .redirectOutput(ProcessBuilder.Redirect.INHERIT)"
    out += "            .start()"
    out += "          if proc.waitFor("
    out += "              checkTimeoutSeconds.toLong,"
    out += "              java.util.concurrent.TimeUnit.SECONDS"
    out += "            )"
    out += "          then Right(proc.exitValue()): Either[String, Int]"
    out += "          else"
    out += "            proc.destroyForcibly()"
    out += "            Left("
    out += "              \"exceeded the \" + checkTimeoutSeconds +"
    out += "                \"s bound and was destroyed\""
    out += "            ): Either[String, Int]"
    out += "        }"
    out += "        .mapError(t =>"
    out += "          RaiderError.OutputValidation("
    out += "            \"check '\" + name + \"' could not start: \" +"
    out += "              String.valueOf(t)"
    out += "          )"
    out += "        )"
    out += "        .flatMap {"
    out += "          case Right(0) => ZIO.unit"
    out += "          case Right(code) =>"
    out += "            ZIO.fail("
    out += "              RaiderError.OutputValidation("
    out += "                \"check '\" + name + \"' failed with exit code \" + code"
    out += "              )"
    out += "            )"
    out += "          case Left(reason) =>"
    out += "            ZIO.fail("
    out += "              RaiderError.DeadlineExceeded("
    out += "                \"check '\" + name + \"' \" + reason"
    out += "              )"
    out += "            )"
    out += "        }"
    out += "    )"
    out += ""
    out += "  /** Deterministic textual report (the Program output). */"
    out += "  private def report(answer: String, input: Input): String ="
    out += "    \"checks passed: \" + trustedChecks.map((name, _, _) => name)"
    out += "      .mkString(\", \") + \"; target=\" + input.task.targetPath + \"\\n\" + answer"
    out += ""
    out += "  // ---- launcher (Option L, ADR-033 §3/§6.3) ----"
    out += ""
    out += "  /** Reads the externalized input (§6.6). Missing/unreadable input is"
    out += "  * a typed failure BEFORE any dispatch."
    out += "  */"
    out += "  def readInputFile(path: String): Task[String] ="
    out += "    Task("
    out += "      ZIO.attemptBlocking("
    out += "        java.nio.file.Files.readString("
    out += "          java.nio.file.Paths.get(path),"
    out += "          java.nio.charset.StandardCharsets.UTF_8"
    out += "        )"
    out += "      ).mapError(t =>"
    out += "        RaiderError.InputValidation("
    out += "          \"CWF-input: cannot read input file '\" + path + \"': \" +"
    out += "            String.valueOf(t)"
    out += "        )"
    out += "      )"
    out += "    )"
    out += ""
    out += "  /** One launcher run: read input -> decode -> build the launch"
    out += "  * services -> run the workflow. Every step is typed; nothing"
    out += "  * dispatches before the agent stage. Build identity of this source"
    out += "  * lives in " + escapeComment(
      name + "-manifest.json"
    ) + "; a manual edit"
    out += "  * invalidates earlier result claims (CWF-D02, wired by RAI-155.d)."
    out += "  */"
    out += "  def runOnce(inputPath: String): Task[Unit] ="
    out += "    for"
    out += "      raw <- readInputFile(inputPath)"
    out += "      input <- decodeOrRefuse(raw)"
    out += "      launch <- buildLaunch(input)"
    out += "      result <- Task(Task.zio(workflow(launch)(raw)))"
    out += "      _ <- Task(ZIO.succeed(println(result)))"
    out += "    yield ()"
    out += ""
    out += "  /** Console adapter (D4): the ONLY place a blocking interpreter run is"
    out += "  * allowed; typed failures become a truthful nonzero exit with"
    out += "  * \"raider CODE: detail\" on stderr. Artifact/exit-table/SIGTERM"
    out += "  * wiring is the RAI-155.d launcher discipline (§7)."
    out += "  */"
    out += "  def runLauncher(args: Seq[String]): ZIO[Any, Nothing, Int] ="
    out += "    val inputPath ="
    // The §6.2 layout publishes `<name>.input.json` next to the source (the
    // slug AS GIVEN); the default input path is exactly that file.
    out += "      if args.isEmpty then \"" + escapeLiteral(
      name + ".input.json"
    ) +
      "\" else args.head"
    out += "    Task.zio(runOnce(inputPath)).either.map {"
    out += "      case Right(()) => 0"
    out += "      case Left(err) =>"
    out += "        System.err.println(\"raider \" + err.code + \": \" + err.detail)"
    out += "        1"
    out += "    }"
    out += ""
    out += "@main def " + launcherMain + "(args: String*): Unit ="
    out += "  // Option L launcher: explicit main binding CURRENT env/config at run"
    out += "  // time; authority is never inherited from the exporting chat session"
    out += "  // (ADR-033 §7). Exit 0 = checks green on the new workspace; nonzero ="
    out += "  // typed failure."
    out += "  zio.Unsafe.unsafe { implicit u =>"
    out += "    val code = zio.Runtime.default.unsafe"
    out += "      .run(" + objectName + ".runLauncher(args))"
    out += "      .getOrThrow()"
    out += "    if code != 0 then sys.exit(code)"
    out += "  }"
    out += ""

    out.mkString("", "\n", "")

  /** The editable prompt template: intent, ordered refinements, then the
    * per-run placeholders. Deterministic.
    */
  private def buildPromptTemplate(s: StsSelection): String =
    val sb = new StringBuilder
    sb.append("Task: ").append(s.intent.text)
    s.refinements.foreach { r =>
      sb.append("\nRefinement: ").append(r.text)
    }
    sb.append("\n\nRun in the workspace {workspace}.")
    sb.append("\nTask input: {task}")
    sb.append("\nTarget path: {targetPath}")
    sb.result()

  private def renderManifest(
      s: StsSelection,
      options: RenderOptions,
      specDigest: String,
      sourceDigest: String
  ): String =
    val turnIds = (s.intent :: s.refinements).map(_.turnId)
    val sb = new StringBuilder
    sb.append("{\n")
    sb.append("  \"schemaVersion\": ")
      .append(ManifestSchemaVersion)
      .append(",\n")
    sb.append("  \"kind\": \"chat-workflow-export\",\n")
    sb.append("  \"generator\": ").append(jsonStr(GeneratorId)).append(",\n")
    sb.append("  \"launcherTemplate\": ")
      .append(jsonStr(options.launcher.manifestName))
      .append(",\n")
    sb.append("  \"source\": {\n")
    sb.append("    \"sessionId\": ").append(jsonStr(s.sessionId)).append(",\n")
    sb.append("    \"turnIds\": [")
    sb.append(turnIds.map(jsonStr).mkString(", "))
    sb.append("],\n")
    sb.append("    \"specDigest\": \"sha256-").append(specDigest).append("\"\n")
    sb.append("  },\n")
    sb.append("  \"buildIdentity\": {\n")
    sb.append("    \"sourceDigest\": \"sha256-")
      .append(sourceDigest)
      .append("\"\n")
    sb.append("  },\n")
    sb.append("  \"origin\": {\n")
    sb.append("    \"providerKind\": ")
      .append(jsonStr(s.origin.providerKind))
      .append(",\n")
    sb.append("    \"model\": ").append(jsonStr(s.origin.model))
    s.origin.baseUrl.foreach { url =>
      sb.append(",\n    \"baseUrl\": ").append(jsonStr(url))
    }
    sb.append("\n  },\n")
    sb.append("  \"params\": {\n")
    RequiredParamNames.zipWithIndex.foreach { (pname, idx) =>
      val value = s.taskParams.find(_.name == pname).map(_.value).getOrElse("")
      sb.append("    \"").append(pname).append("\": ").append(jsonStr(value))
      if idx < RequiredParamNames.length - 1 then sb.append(",")
      sb.append("\n")
    }
    sb.append("  },\n")
    sb.append("  \"checks\": [\n")
    s.checks.zipWithIndex.foreach { (c, idx) =>
      sb.append("    {\n")
      sb.append("      \"name\": ").append(jsonStr(c.name)).append(",\n")
      sb.append("      \"argv\": [")
        .append(c.argv.map(jsonStr).mkString(", "))
        .append("],\n")
      sb.append("      \"advisory\": ")
        .append(c.advisory.toString)
        .append("\n")
      sb.append("    }")
      if idx < s.checks.length - 1 then sb.append(",")
      sb.append("\n")
    }
    sb.append("  ],\n")
    sb.append("  \"note\": ")
      .append(
        jsonStr(
          "manifest describes provenance only; it is never executed (ADR-033 §6.4)"
        )
      )
      .append("\n")
    sb.append("}\n")
    sb.result()

  private def renderInputExample(s: StsSelection): String =
    val targetPath =
      s.taskParams.find(_.name == "targetPath").map(_.value).getOrElse("")
    val summary =
      s.taskParams.find(_.name == "summary").map(_.value).getOrElse("")
    val sb = new StringBuilder
    sb.append("{\n")
    sb.append("  \"schemaVersion\": ").append(InputSchemaVersion).append(",\n")
    sb.append("  \"workspace\": \"/absolute/path/to/fresh-workspace-copy\",\n")
    sb.append("  \"task\": {\n")
    sb.append("    \"targetPath\": ").append(jsonStr(targetPath)).append(",\n")
    sb.append("    \"summary\": ").append(jsonStr(summary)).append("\n")
    sb.append("  }\n")
    sb.append("}\n")
    sb.result()

  private def capitalize(s: String): String =
    if s.isEmpty then s
    else s.charAt(0).toUpper.toString + s.substring(1)

  private def decapitalize(s: String): String =
    if s.isEmpty then s
    else s.charAt(0).toLower.toString + s.substring(1)

  /** Scala 3 hard keywords the generated identifiers must never collide with
    * (conservative set; the slug step has already sanitized shape).
    */
  private val ReservedWords: Set[String] = Set(
    "abstract",
    "case",
    "catch",
    "class",
    "def",
    "do",
    "else",
    "enum",
    "export",
    "extends",
    "false",
    "final",
    "finally",
    "for",
    "given",
    "if",
    "implicit",
    "import",
    "infix",
    "inline",
    "lazy",
    "match",
    "macro",
    "new",
    "null",
    "object",
    "opaque",
    "open",
    "override",
    "package",
    "private",
    "protected",
    "pure",
    "quoted",
    "return",
    "sealed",
    "super",
    "then",
    "this",
    "throw",
    "trait",
    "transparent",
    "try",
    "true",
    "type",
    "val",
    "var",
    "while",
    "with",
    "yield"
  )

end ChatWorkflowRenderer
