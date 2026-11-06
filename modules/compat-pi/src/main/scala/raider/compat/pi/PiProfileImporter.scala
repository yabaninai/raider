package raider.compat.pi

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

import zio.IO
import zio.ZIO

import scala.collection.mutable.Builder

/** pi agent-profile corpus importer (card RAI-032).
  *
  * Reads the vendored pi subagent example corpus (e.g.
  * `vendor/pi-profiles/upstream`) and produces an explicit compatibility
  * report: profiles with mapped tool policies, workflow prompts, reference
  * files, declared parity gaps, declared degraded mappings and honesty counts.
  * Nothing is silently dropped: unknown tools, unknown frontmatter keys,
  * unsupported shapes and unknown files are enumerated as rejected or ignored
  * entries (PIIMP-02).
  *
  * Determinism: corpus iteration is path-sorted, list outputs are sorted by
  * their natural keys, and the report contains no timestamps, so re-importing
  * an unchanged corpus yields an identical report and snapshotId (PIIMP-03).
  */
object PiProfileImporter:

  private type Entries = Builder[FieldEntry, List[FieldEntry]]

  /** Declared cross-cutting parity gaps of the import (PIIMP-02 honesty). These
    * describe the mapping table itself, not one specific corpus, so they are
    * always present.
    */
  private val ParityGaps: List[PiSectionEntry] = List(
    PiSectionEntry(
      "profile worker",
      "pi's default child toolset grants file-write ('write'); Raider has " +
        "no single-call file-create tool, so imported default policies " +
        "lack it (rejected, not approximated)"
    ),
    PiSectionEntry(
      "piDefault extension tools",
      "pi's default child toolset also includes extension-provided tools; " +
        "only pi built-ins with Raider equivalents are imported - imported " +
        "workers are NOT guaranteed full pi parity"
    )
  )

  /** Declared degraded mappings of the import (strictly narrower than the pi
    * capability they stand in for). Always present.
    */
  private val Degraded: List[PiSectionEntry] = List(
    PiSectionEntry(
      "tool bash",
      "pi bash takes a free-form shell command; Raider proc_run executes " +
        "structured argv with no shell, an empty environment and an " +
        "explicit allowlist - the mapped capability is strictly narrower"
    )
  )

  /** Import the corpus rooted at `root` (the directory that directly contains
    * `agents/`, `prompts/`, `README.md`, `agents.ts`, `LICENSE`). Fails with
    * PiImportError.CorpusMissing when the root does not exist or contains no
    * regular files.
    */
  def importCorpus(
      root: Path,
      provenance: PiProvenance
  ): IO[PiImportError, PiImportReport] =
    ZIO
      .attempt {
        if !Files.isDirectory(root) then throw PiImportError.CorpusMissing(root)
        else
          import scala.jdk.CollectionConverters.*
          val walk = Files.walk(root)
          try
            val rels = walk
              .iterator()
              .asScala
              .filter(Files.isRegularFile(_))
              .map(p => root.relativize(p).toString.replace('\\', '/'))
              .toList
              .sorted
            if rels.isEmpty then throw PiImportError.CorpusMissing(root)
            else buildReport(root, provenance, rels)
          finally walk.close()
      }
      .foldZIO(
        err =>
          err match
            case missing: PiImportError.CorpusMissing => ZIO.fail(missing)
            case other                                =>
              // Environment/IO failure outside the typed error surface is a
              // defect, not a typed corpus error.
              ZIO.die(other)
        ,
        report => ZIO.succeed(report)
      )

  /** Corpus roles; anything else becomes a rejected file entry. */
  private def roleOf(rel: String): Option[String] =
    if rel.startsWith("agents/") && rel.endsWith(".md") &&
      rel.length > "agents/.md".length
    then Some("agentProfile")
    else if rel.startsWith("prompts/") && rel.endsWith(".md") &&
      rel.length > "prompts/.md".length
    then Some("workflowPrompt")
    else
      rel match
        case "README.md" => Some("corpusDoc")
        case "agents.ts" => Some("discoverySemantics")
        case "LICENSE"   => Some("license")
        case _           => None

  private def buildReport(
      root: Path,
      provenance: PiProvenance,
      rels: List[String]
  ): PiImportReport =
    val inventory = rels.map { rel =>
      rel -> scala.util.Try(Files.readAllBytes(root.resolve(rel)))
    }

    val corpusFiles = inventory.collect {
      case (rel, scala.util.Success(bytes)) =>
        CorpusFile(
          rel,
          Hashing.sha256Hex(bytes),
          roleOf(rel).getOrElse("unknown")
        )
    }
    val embeddedProvenance = provenance.copy(corpusFiles = corpusFiles)

    val profiles = List.newBuilder[PiImportedProfile]
    val prompts = List.newBuilder[PiImportedPrompt]
    val references = List.newBuilder[PiReferenceFile]
    val rejected = List.newBuilder[PiRejectedFile]

    inventory.foreach { (rel, bytesTry) =>
      bytesTry match
        case scala.util.Failure(err) =>
          rejected += PiRejectedFile(
            rel,
            s"unreadable: ${err.getClass.getSimpleName}"
          )
        case scala.util.Success(bytes) =>
          roleOf(rel) match
            case Some("agentProfile") =>
              importAgent(rel, new String(bytes, UTF_8)) match
                case Right(p)     => profiles += p
                case Left(reason) => rejected += PiRejectedFile(rel, reason)
            case Some("workflowPrompt") =>
              importPrompt(rel, new String(bytes, UTF_8)) match
                case Right(p)     => prompts += p
                case Left(reason) => rejected += PiRejectedFile(rel, reason)
            case Some("corpusDoc") =>
              references += PiReferenceFile(
                rel,
                "corpusDoc",
                "corpus documentation; not a profile or prompt"
              )
            case Some("discoverySemantics") =>
              references += PiReferenceFile(
                rel,
                "discoverySemantics",
                "pi agent-discovery source; defines the frontmatter parse " +
                  "semantics mirrored by the bounded importer parser"
              )
            case Some("license") =>
              references += PiReferenceFile(
                rel,
                "license",
                "license compliance copy; notices live in THIRD_PARTY_NOTICES.md"
              )
            case Some(other) =>
              rejected += PiRejectedFile(rel, s"unhandled corpus role: $other")
            case None =>
              rejected += PiRejectedFile(
                rel,
                "unknown corpus entry (expected agents/*.md, prompts/*.md, " +
                  "README.md, agents.ts or LICENSE)"
              )
    }

    val profileList = profiles.result().sortBy(_.name)
    val promptList = prompts.result().sortBy(_.name)
    val referenceList = references.result().sortBy(_.path)
    val rejectedList = rejected.result().sortBy(_.path)

    val counts = Counts(
      supported = profileList.map(_.supported.size).sum +
        profileList.map(_.toolPolicy.mapped.size).sum +
        promptList.map(_.supported.size).sum,
      ignored = profileList.map(_.ignored.size).sum +
        promptList.map(_.ignored.size).sum +
        referenceList.size,
      rejected = profileList.map(_.rejected.size).sum +
        promptList.map(_.rejected.size).sum +
        profileList.map(_.toolPolicy.unsupported.size).sum +
        rejectedList.size
    )

    PiImportReport(
      schemaVersion = PiImportReport.SchemaVersion,
      provenance = embeddedProvenance,
      snapshotId = snapshotId(embeddedProvenance),
      profiles = profileList,
      workflowPrompts = promptList,
      referenceFiles = referenceList,
      parityGaps = ParityGaps,
      degraded = Degraded,
      rejectedFiles = rejectedList,
      counts = counts
    )

  /** sha256 over the pinned upstream commit and the sorted corpus file hashes;
    * any corpus change (or pin change) yields a new snapshot id.
    */
  private def snapshotId(provenance: PiProvenance): String =
    val material = new StringBuilder
    material ++= s"commit=${provenance.upstreamCommit}\n"
    provenance.corpusFiles.foreach { f =>
      material ++= s"file ${f.path} ${f.sha256}\n"
    }
    Hashing.sha256Hex(material.toString.getBytes(UTF_8))

  private val ModeledKeys: Set[String] =
    Set("name", "description", "tools", "model")

  private def missingField(name: String): String =
    s"missing or non-scalar frontmatter field '$name'"

  /** Resolve the `tools` frontmatter field to a (kind, classified) pair; Left
    * carries the unsupported-shape detail (field-level rejection).
    */
  private def toolsPolicy(
      value: Option[PiFrontmatter.Value]
  ): Either[String, (String, List[PiToolMap.Classified])] =
    value match
      case None =>
        Right("piDefault", PiToolMap.piDefaultTools.map(PiToolMap.classify))
      case Some(PiFrontmatter.Scalar("")) =>
        Right("piDefault", PiToolMap.piDefaultTools.map(PiToolMap.classify))
      case Some(PiFrontmatter.Scalar(csv)) =>
        val names = csv.split(',').toList.map(_.trim).filter(_.nonEmpty)
        Right("explicit", names.map(PiToolMap.classify))
      case Some(PiFrontmatter.ScalarList(items)) =>
        Right("explicit", items.map(PiToolMap.classify))
      case Some(PiFrontmatter.UnsupportedShape(detail)) =>
        Left(detail)

  /** Human-readable declaration text for the supported `tools` entry. */
  private def toolsDeclaration(value: Option[PiFrontmatter.Value]): String =
    value match
      case None => "not declared; pi default built-ins assumed"
      case Some(PiFrontmatter.Scalar("")) =>
        "declared empty; pi default built-ins assumed"
      case Some(PiFrontmatter.Scalar(csv)) => s"declared: $csv"
      case Some(PiFrontmatter.ScalarList(items)) =>
        s"declared inline array: [${items.mkString(", ")}]"
      case Some(PiFrontmatter.UnsupportedShape(_)) =>
        "declared in an unsupported shape"

  private def importAgent(
      sourcePath: String,
      text: String
  ): Either[String, PiImportedProfile] =
    PiFrontmatter.parse(text).flatMap { doc =>
      val fields = doc.fields.toMap
      def scalar(fieldName: String): Option[String] =
        fields.get(fieldName).collect { case PiFrontmatter.Scalar(s) => s }

      val name = scalar("name").filter(_.nonEmpty)
      val description = scalar("description").filter(_.nonEmpty)
      (name, description) match
        case (None, _) => Left(missingField("name"))
        case (_, None) => Left(missingField("description"))
        case (Some(name), Some(description)) =>
          val supported: Entries = List.newBuilder
          val ignored: Entries = List.newBuilder
          val rejected: Entries = List.newBuilder

          supported += FieldEntry("name", "imported as the profile name")
          supported +=
            FieldEntry("description", "imported as the profile description")

          val toolsValue = fields.get("tools")
          val policy: PiToolPolicy = toolsPolicy(toolsValue) match
            case Left(detail) =>
              rejected += FieldEntry("tools", detail)
              PiToolPolicy("rejected", Nil, Nil)
            case Right((kind, classified)) =>
              supported += FieldEntry(
                "tools",
                toolsDeclaration(toolsValue) +
                  (if kind == "piDefault" then
                     s" (${PiToolMap.piDefaultTools.mkString(", ")})"
                   else "")
              )
              val mapped = classified
                .collect { case PiToolMap.Mapped(pi, target) =>
                  ToolMapping(pi, target.raider, target.degraded, target.note)
                }
                .sortBy(_.pi)
              val unsupported = classified
                .collect { case PiToolMap.Unsupported(pi, reason) =>
                  UnsupportedTool(pi, reason)
                }
                .sortBy(_.pi)
              PiToolPolicy(kind, mapped, unsupported)

          val model = scalar("model").filter(_.nonEmpty)
          model.foreach { m =>
            supported += FieldEntry("model", s"requested upstream model: $m")
          }

          val body = doc.body.trim
          if body.isEmpty then
            Left("empty markdown body: no system prompt to import")
          else
            supported += FieldEntry(
              "systemPrompt",
              "markdown body imported verbatim as the profile system prompt"
            )
            doc.fields.foreach { (k, _) =>
              if !ModeledKeys.contains(k) then
                ignored += FieldEntry(
                  k,
                  "unknown frontmatter key: not modeled by the Raider " +
                    "import (raw value kept out of the profile)"
                )
            }
            Right(
              PiImportedProfile(
                name = name,
                sourcePath = sourcePath,
                description = description,
                systemPrompt = body,
                requestedModel = model.getOrElse(""),
                toolPolicy = policy,
                supported = supported.result(),
                ignored = ignored.result().sortBy(_.field),
                rejected = rejected.result().sortBy(_.field)
              )
            )
    }

  private def importPrompt(
      sourcePath: String,
      text: String
  ): Either[String, PiImportedPrompt] =
    PiFrontmatter.parse(text).flatMap { doc =>
      val description = doc.fields.toMap.get("description").collect {
        case PiFrontmatter.Scalar(s) if s.nonEmpty => s
      }
      val body = doc.body.trim
      val base = sourcePath.stripPrefix("prompts/").stripSuffix(".md")
      description match
        case None => Left(missingField("description"))
        case Some(description) =>
          if body.isEmpty then
            Left("empty markdown body: no prompt text to import")
          else
            Right(
              PiImportedPrompt(
                name = base,
                sourcePath = sourcePath,
                description = description,
                promptText = body,
                supported = List(
                  FieldEntry(
                    "description",
                    "imported as the workflow description"
                  ),
                  FieldEntry(
                    "promptText",
                    "markdown body imported verbatim as the prompt template text"
                  )
                ),
                ignored = List(
                  FieldEntry(
                    "$@ argument substitution",
                    "pi slash-command argument placeholder; the Raider " +
                      "import records the text only"
                  ),
                  FieldEntry(
                    "{previous} chain placeholder",
                    "pi subagent chain output piping; chain execution is " +
                      "pi-runtime semantics and is not imported"
                  )
                ),
                rejected = Nil
              )
            )
    }
