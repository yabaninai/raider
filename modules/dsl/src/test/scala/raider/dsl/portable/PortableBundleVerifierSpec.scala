package raider.dsl.portable

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Arrays

import zio.ZIO
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import raider.core.{Program, Task}
import raider.dsl.workflow.{
  StsAgentStage,
  StsOrigin,
  StsParam,
  StsSelection,
  StsTurn,
  StsTurnKind,
  StsTurnStatus
}

/** RAI-214 launcher-side manifest digest verification. The fixtures build REAL
  * published bundles through the producer (`PortableChatExport.exportTo`) so
  * the round-trip producer -> verifier is exercised, not a hand-written
  * approximation. Every refusal case asserts the typed refusal AND the
  * sequencing discipline (the refusal precedes any lowering work). FS facts are
  * captured into vals BEFORE cleanup (the assertTrue macro re-evaluates
  * lazily).
  */
object PortableBundleVerifierSpec extends ZIOSpecDefault:

  private val Name = "portable-task"
  private val DocumentName = Name + ".workflow.json"
  private val ManifestName = Name + "-manifest.json"
  private val InputName = Name + ".input.json"

  private val snapshot = "sha256:" + ("ab" * 32)

  private val target = PortableExportTarget(
    profile = "coder",
    toolset = "workspace",
    checkId = "workspace-marker-present",
    registrySnapshot = snapshot
  )

  private val selection: StsSelection = StsSelection(
    sessionId = "sess-px",
    intent = StsTurn(
      "t-1",
      StsTurnKind.Intent,
      StsTurnStatus.Completed,
      "make module X compile and keep checks green"
    ),
    refinements = List(
      StsTurn(
        "t-2",
        StsTurnKind.Refinement,
        StsTurnStatus.Completed,
        "do not touch the public API"
      )
    ),
    taskParams = List(
      StsParam("targetPath", "modules/x"),
      StsParam("summary", "one-paragraph run summary")
    ),
    checks = Nil,
    agentStage = StsAgentStage(2),
    origin = StsOrigin(
      "openai-compatible",
      "test-model",
      Some("http://127.0.0.1:1/v1")
    )
  )

  /** Trusted-resolution-only registry: lowering green requires the pinned
    * profile and check/snapshot names to EXIST; the fixture programs are never
    * applied (no model call, no paid call).
    */
  private val identityProgram: Program[Json, Json] =
    Program.fromFunction[Json, Json]("fixture-identity")(input =>
      Task.succeed(input)
    )

  private val registry = PortableProgramRegistry(
    agents = Map(target.profile -> identityProgram),
    checks = Map((target.checkId, snapshot) -> identityProgram)
  )

  // -- fixture helpers -------------------------------------------------------

  /** Builds a REAL published bundle under `tmp/out` through the producer. */
  private def exportBundle(tmp: Path): PortableExportResult =
    PortableChatExport
      .exportTo(
        selection,
        PortableExportOptions(Name),
        target,
        tmp.resolve("out"),
        1750000000000L
      ) match
      case Right(published) => published
      case Left(err) => throw err // typed fixture failure; never swallowed

  private def cleanup(tmp: Path): Unit =
    Files
      .walk(tmp)
      .sorted(java.util.Comparator.reverseOrder[Path]())
      .forEach(p => Files.deleteIfExists(p))

  private def withTempBundle[A](use: (Path, PortableExportResult) => A): A =
    val tmp = Files.createTempDirectory("rai214-bundle-verify")
    try use(tmp, exportBundle(tmp))
    finally cleanup(tmp)

  /** ZIO-scoped variant for test bodies that RETURN effects: the fixture is
    * acquired at runtime and the temp tree is released AFTER the effect runs
    * (the plain variant would clean up at construction time).
    */
  private def withTempBundleZIO(
      use: (Path, PortableExportResult) => ZIO[Any, Throwable, TestResult]
  ): ZIO[Any, Throwable, TestResult] =
    ZIO.scoped {
      ZIO
        .acquireRelease(
          ZIO.attempt {
            val tmp = Files.createTempDirectory("rai214-bundle-verify")
            (tmp, exportBundle(tmp))
          }
        )(res => ZIO.attemptBlocking(cleanup(res._1)).orDie)
        .flatMap { case (tmp, published) => use(tmp, published) }
    }

  private def bundleOf(published: PortableExportResult): PortableBundlePaths =
    PortableBundlePaths(published.targetDir, ManifestName)

  private def appendByte(path: Path): Unit =
    Files.write(path, Files.readAllBytes(path) :+ 32.toByte)

  private def writeString(path: Path, content: String): Unit =
    Files.write(path, content.getBytes(StandardCharsets.UTF_8))

  private def replaceSection(
      root: Json.Obj,
      section: String,
      value: Json
  ): Json.Obj =
    Json.Obj(root.fields.map { case (k, v) =>
      if k == section then section -> value else k -> v
    }*)

  private def rewriteManifest(bundleDir: Path)(f: Json.Obj => Json.Obj): Unit =
    val manifestPath = bundleDir.resolve(ManifestName)
    val json = Files.readString(manifestPath).fromJson[Json].toOption.get
    writeString(manifestPath, f(json.asInstanceOf[Json.Obj]).toJson)

  /** A schema-valid manifest whose document digest is WRONG (valid pattern,
    * wrong value) — verification must catch it against the disk bytes.
    */
  private val wrongDigest = "sha256:" + ("cd" * 32)

  /** A schema-valid manifest inflated past the 64 KiB verification bound via
    * the 4096 x 128 refinementTurnIds allowance — the size bound must fire
    * BEFORE parsing.
    */
  private def oversizedManifestJson: Json =
    Json.Obj(
      "schemaVersion" -> Json.Num(BigDecimal(1)),
      "kind" -> Json.Str(PortableChatExport.ManifestKind),
      "exportedAtMs" -> Json.Num(BigDecimal(1750000000000L)),
      "sessionId" -> Json.Str("sess-px"),
      "origin" -> Json.Obj(
        "providerKind" -> Json.Str("openai-compatible"),
        "model" -> Json.Str("test-model"),
        "baseUrl" -> Json.Null
      ),
      "selection" -> Json.Obj(
        "intentTurnId" -> Json.Str("t-1"),
        "refinementTurnIds" -> Json.Arr(
          (1 to 4096).map(i => Json.Str("r" * 128))*
        ),
        "maxRoundsBound" -> Json.Num(BigDecimal(2))
      ),
      "document" -> Json.Obj(
        "fileName" -> Json.Str(DocumentName),
        "sha256" -> Json.Str("sha256:" + ("ab" * 32))
      ),
      "inputExample" -> Json.Obj(
        "fileName" -> Json.Str(InputName),
        "sha256" -> Json.Str("sha256:" + ("ab" * 32))
      ),
      "trusted" -> Json.Obj(
        "profile" -> Json.Str("coder"),
        "toolset" -> Json.Str("workspace"),
        "checkId" -> Json.Str("workspace-marker-present"),
        "registrySnapshot" -> Json.Str("sha256:" + ("ab" * 32))
      )
    )

  /** Verifies, then (only on success) validates + lowers the VERIFIED bytes —
    * the exact sequencing the launcher must keep; `reached` records whether
    * lowering work was attempted.
    */
  private def verifyThenLower(
      bundle: PortableBundlePaths,
      reached: scala.collection.mutable.ListBuffer[String]
  ): Either[PortableBundleRefusal, PortableLoweredWorkflow] =
    for
      verified <- PortableBundleVerifier.verifyBundle(bundle)
      _ <- {
        reached += "lower"
        Right(())
      }
      validated <- PortableWorkflowValidator
        .validate(verified.documentBytes)
        .left
        .map(d => PortableBundleRefusal.ManifestInvalid(ManifestName, d.code))
      lowered <- PortableWorkflowLowerer.lower(validated, registry).left.map {
        d =>
          reached += "lower-refused"
          PortableBundleRefusal.ManifestInvalid(ManifestName, d.code)
      }
    yield lowered

  def spec = suite("PortableBundleVerifier (RAI-214)")(
    test(
      "detectForDocument: export naming engages the bundle contract, plain documents do not"
    ) {
      withTempBundle { (tmp, published) =>
        val docPath = published.targetDir.resolve(DocumentName)
        val detected = PortableBundleVerifier.detectForDocument(docPath)
        val plainDir = tmp.resolve("plain")
        Files.createDirectory(plainDir)
        val plainDoc = plainDir.resolve(DocumentName)
        writeString(plainDoc, "{}")
        val plain = PortableBundleVerifier.detectForDocument(plainDoc)
        val foreignDoc = plainDir.resolve("report.json")
        writeString(foreignDoc, "{}")
        writeString(plainDir.resolve("report-manifest.json"), "{}")
        val foreign = PortableBundleVerifier.detectForDocument(foreignDoc)
        assertTrue(
          detected.contains(
            PortableBundlePaths(published.targetDir, ManifestName)
          ),
          plain.isEmpty,
          foreign.isEmpty
        )
      }
    },
    test(
      "detectForDocument: a non-regular manifest sibling is detected, then refused typed"
    ) {
      withTempBundle { (tmp, _) =>
        val weird = tmp.resolve("weird")
        Files.createDirectory(weird)
        writeString(weird.resolve(DocumentName), "{}")
        Files.createDirectory(weird.resolve(ManifestName))
        val detected =
          PortableBundleVerifier.detectForDocument(weird.resolve(DocumentName))
        val refused = detected.map(PortableBundleVerifier.verifyBundle)
        assertTrue(
          detected.isDefined,
          refused.exists(_.left.exists {
            case PortableBundleRefusal.EntryNotRegularFile(name) =>
              name == ManifestName
            case _ => false
          })
        )
      }
    },
    test(
      "a valid published bundle verifies green and returns the pinned bytes"
    ) {
      withTempBundle { (_, published) =>
        val verified = PortableBundleVerifier.verifyBundle(bundleOf(published))
        val docBytes =
          Files.readAllBytes(published.targetDir.resolve(DocumentName))
        val inBytes = Files.readAllBytes(published.targetDir.resolve(InputName))
        verified match
          case Right(v) =>
            // The export result reports identity digests as "sha256-<hex>";
            // the manifest pins "sha256:<hex>". Same hex, two conventions.
            assertTrue(
              v.documentSha256 == "sha256:" + published.documentSha256
                .stripPrefix("sha256-"),
              v.inputExampleSha256 == "sha256:" + published.inputExampleSha256
                .stripPrefix("sha256-"),
              Arrays.equals(v.documentBytes, docBytes),
              Arrays.equals(v.inputExampleBytes, inBytes),
              v.documentFileName == DocumentName,
              v.inputExampleFileName == InputName
            )
          case Left(refusal) =>
            assertTrue(false, ("unexpected refusal: " + refusal.message) != "")
      }
    },
    test(
      "verified bytes validate and lower with a pinned registry (the pre-wiring launcher flow)"
    ) {
      withTempBundle { (_, published) =>
        val reached = scala.collection.mutable.ListBuffer.empty[String]
        val lowered = verifyThenLower(bundleOf(published), reached)
        val structOk = lowered.toOption.map(_.structure) match
          case Some(node) =>
            node.kind == "sequence" &&
            node.children.map(_.kind) == List("agent", "trustedCheck")
          case None => false
        assertTrue(
          lowered.isRight,
          structOk,
          reached == List("lower")
        )
      }
    },
    test("tampered document bytes refuse typed BEFORE any lowering work") {
      withTempBundle { (_, published) =>
        appendByte(published.targetDir.resolve(DocumentName))
        val reached = scala.collection.mutable.ListBuffer.empty[String]
        val outcome = verifyThenLower(bundleOf(published), reached)
        val refusal = outcome.left.toOption
        assertTrue(
          refusal.exists {
            case PortableBundleRefusal.DigestMismatch(name, _, _) =>
              name == DocumentName
            case _ => false
          },
          refusal.exists(_.code == "bundle-digest-mismatch"),
          reached.isEmpty
        )
      }
    },
    test("tampered input example bytes refuse typed BEFORE any lowering work") {
      withTempBundle { (_, published) =>
        appendByte(published.targetDir.resolve(InputName))
        val reached = scala.collection.mutable.ListBuffer.empty[String]
        val outcome = verifyThenLower(bundleOf(published), reached)
        assertTrue(
          outcome.left.exists {
            case PortableBundleRefusal.DigestMismatch(name, _, _) =>
              name == InputName
            case _ => false
          },
          outcome.left.forall(_.code == "bundle-digest-mismatch"),
          reached.isEmpty
        )
      }
    },
    test(
      "missing bundle files refuse typed (document, input example, manifest)"
    ) {
      withTempBundle { (_, published) =>
        val dir = published.targetDir
        val docGone = {
          Files.deleteIfExists(dir.resolve(DocumentName))
          PortableBundleVerifier.verifyBundle(bundleOf(published))
        }
        Files.writeString(dir.resolve(DocumentName), "{}")
        val inputGone = {
          Files.deleteIfExists(dir.resolve(InputName))
          PortableBundleVerifier.verifyBundle(bundleOf(published))
        }
        Files.writeString(dir.resolve(InputName), "{}")
        val manifestGone = {
          Files.deleteIfExists(dir.resolve(ManifestName))
          PortableBundleVerifier.verifyBundle(bundleOf(published))
        }
        assertTrue(
          docGone.left.exists {
            case PortableBundleRefusal.EntryMissing(name) =>
              name == DocumentName
            case _ => false
          },
          inputGone.left.exists {
            case PortableBundleRefusal.EntryMissing(name) => name == InputName
            case _                                        => false
          },
          manifestGone.left.exists {
            case PortableBundleRefusal.ManifestMissing(name) =>
              name == ManifestName
            case _ => false
          }
        )
      }
    },
    test(
      "extra unmanifested entries refuse typed (file, subdirectory, symlink)"
    ) {
      withTempBundle { (_, published) =>
        val dir = published.targetDir
        val extraFile = {
          writeString(dir.resolve("notes.txt"), "unmanifested")
          PortableBundleVerifier.verifyBundle(bundleOf(published))
        }
        Files.deleteIfExists(dir.resolve("notes.txt"))
        val extraDir = {
          Files.createDirectory(dir.resolve("extra-dir"))
          PortableBundleVerifier.verifyBundle(bundleOf(published))
        }
        Files.deleteIfExists(dir.resolve("extra-dir"))
        val extraLink = {
          Files.createSymbolicLink(
            dir.resolve("extra-link"),
            dir.resolve(DocumentName)
          )
          PortableBundleVerifier.verifyBundle(bundleOf(published))
        }
        assertTrue(
          extraFile.left.exists {
            case PortableBundleRefusal.ExtraEntries(names) =>
              names == Vector("notes.txt")
            case _ => false
          },
          extraDir.left.exists {
            case PortableBundleRefusal.ExtraEntries(names) =>
              names == Vector("extra-dir")
            case _ => false
          },
          extraLink.left.exists {
            case PortableBundleRefusal.ExtraEntries(names) =>
              names == Vector("extra-link")
            case _ => false
          }
        )
      }
    },
    test(
      "a manifest-named entry replaced by a symlink refuses typed (regular files only)"
    ) {
      withTempBundle { (tmp, published) =>
        val dir = published.targetDir
        val keptDoc = tmp.resolve("elsewhere-workflow.json")
        Files.write(keptDoc, Files.readAllBytes(dir.resolve(DocumentName)))
        Files.deleteIfExists(dir.resolve(DocumentName))
        val symlinked = {
          Files.createSymbolicLink(dir.resolve(DocumentName), keptDoc)
          PortableBundleVerifier.verifyBundle(bundleOf(published))
        }
        val dangling = {
          Files.deleteIfExists(dir.resolve(DocumentName))
          Files.createSymbolicLink(
            dir.resolve(DocumentName),
            tmp.resolve("absent.json")
          )
          PortableBundleVerifier.verifyBundle(bundleOf(published))
        }
        // A dangling symlink is non-regular either way: NOFOLLOW attributes
        // report the link itself on some platforms (EntryNotRegularFile),
        // others see nothing behind the name (EntryMissing). Both are honest
        // typed refusals; the contract is that it never verifies green.
        val danglingRefusal = dangling.left.toOption
        assertTrue(
          symlinked.left.exists {
            case PortableBundleRefusal.EntryNotRegularFile(name) =>
              name == DocumentName
            case _ => false
          },
          danglingRefusal.exists {
            case PortableBundleRefusal.EntryNotRegularFile(name) =>
              name == DocumentName
            case PortableBundleRefusal.EntryMissing(name) =>
              name == DocumentName
            case _ => false
          }
        )
      }
    },
    test(
      "a schema-valid manifest with a wrong digest refuses typed against the disk bytes"
    ) {
      withTempBundle { (_, published) =>
        rewriteManifest(published.targetDir)(root =>
          replaceSection(
            root,
            "document",
            Json.Obj(
              "fileName" -> Json.Str(DocumentName),
              "sha256" -> Json.Str(wrongDigest)
            )
          )
        )
        val outcome = PortableBundleVerifier.verifyBundle(bundleOf(published))
        assertTrue(
          outcome.left.exists {
            case PortableBundleRefusal.DigestMismatch(name, expected, _) =>
              name == DocumentName && expected == wrongDigest
            case _ => false
          }
        )
      }
    },
    test("a malformed or wrong-kind manifest refuses typed") {
      withTempBundle { (_, published) =>
        val wrongKind = {
          rewriteManifest(published.targetDir)(root =>
            replaceSection(root, "kind", Json.Str("some.other.kind"))
          )
          PortableBundleVerifier.verifyBundle(bundleOf(published))
        }
        // Destroys the manifest LAST: the malformed text cannot be rewritten.
        val malformed = {
          writeString(published.targetDir.resolve(ManifestName), "{not json")
          PortableBundleVerifier.verifyBundle(bundleOf(published))
        }
        assertTrue(
          malformed.left.exists {
            case PortableBundleRefusal.ManifestInvalid(name, _) =>
              name == ManifestName
            case _ => false
          },
          wrongKind.left.exists {
            case PortableBundleRefusal.ManifestInvalid(name, reason) =>
              name == ManifestName && reason.contains("kind")
            case _ => false
          }
        )
      }
    },
    test("an oversized manifest refuses on the size bound BEFORE parsing") {
      withTempBundle { (_, published) =>
        writeString(
          published.targetDir.resolve(ManifestName),
          oversizedManifestJson.toJson
        )
        val size = Files.size(published.targetDir.resolve(ManifestName))
        val outcome = PortableBundleVerifier.verifyBundle(bundleOf(published))
        assertTrue(
          size > PortableBundleVerifier.MaxManifestBytes,
          outcome.left.exists {
            case PortableBundleRefusal.ManifestOversized(name, bytes, cap) =>
              name == ManifestName && bytes == size && cap == PortableBundleVerifier.MaxManifestBytes
            case _ => false
          }
        )
      }
    },
    test("an over-full bundle directory refuses on the listing bound") {
      withTempBundle { (_, published) =>
        val dir = published.targetDir
        (0 until PortableBundleVerifier.MaxBundleEntries - 2).foreach { i =>
          writeString(dir.resolve("filler-%02d.txt".format(i)), "filler")
        }
        val outcome = PortableBundleVerifier.verifyBundle(bundleOf(published))
        assertTrue(
          outcome.left.exists {
            case PortableBundleRefusal.BundleTooLarge(count, cap) =>
              count > cap && cap == PortableBundleVerifier.MaxBundleEntries
            case _ => false
          }
        )
      }
    },
    test("manifest-declared names that cannot close the bundle refuse typed") {
      withTempBundle { (_, published) =>
        val collidingEntries = {
          rewriteManifest(published.targetDir)(root =>
            replaceSection(
              root,
              "inputExample",
              Json.Obj(
                "fileName" -> Json.Str(DocumentName),
                "sha256" -> Json.Str("sha256:" + ("ab" * 32))
              )
            )
          )
          PortableBundleVerifier.verifyBundle(bundleOf(published))
        }
        val collidesWithManifest = {
          rewriteManifest(published.targetDir)(root =>
            replaceSection(
              root,
              "document",
              Json.Obj(
                "fileName" -> Json.Str(ManifestName),
                "sha256" -> Json.Str("sha256:" + ("ab" * 32))
              )
            )
          )
          PortableBundleVerifier.verifyBundle(bundleOf(published))
        }
        assertTrue(
          collidingEntries.left.exists {
            case PortableBundleRefusal.DeclaredNamesInvalid(reason) =>
              reason.contains(DocumentName)
            case _ => false
          },
          collidesWithManifest.left.exists {
            case PortableBundleRefusal.DeclaredNamesInvalid(reason) =>
              reason.contains(ManifestName)
            case _ => false
          }
        )
      }
    },
    test(
      "concurrent verifications of one bundle are independent, deterministic and bounded"
    ) {
      withTempBundleZIO { (_, published) =>
        val bundle = bundleOf(published)
        val parallel = ZIO.foreachPar(1 to 8) { _ =>
          ZIO.attempt(PortableBundleVerifier.verifyBundle(bundle))
        }
        parallel.map { results =>
          val digests = results.collect { case Right(v) =>
            v.documentSha256
          }.toSet
          val codes = results.collect { case Left(r) => r.code }.toSet
          assertTrue(
            results.count(_.isRight) == 8,
            digests.size == 1,
            codes.isEmpty
          )
        }
      }
    },
    test("concurrent verifications of tampered copies refuse identically") {
      withTempBundleZIO { (tmp, published) =>
        val tampered = ZIO.attempt {
          (1 to 8).map { i =>
            val dir = tmp.resolve("tampered-" + i)
            Files.createDirectory(dir)
            Seq(DocumentName, ManifestName, InputName).foreach { n =>
              Files.copy(published.targetDir.resolve(n), dir.resolve(n))
            }
            appendByte(dir.resolve(InputName))
            PortableBundlePaths(dir, ManifestName)
          }
        }
        tampered
          .flatMap { bundles =>
            ZIO.foreachPar(bundles) { b =>
              ZIO.attempt(PortableBundleVerifier.verifyBundle(b))
            }
          }
          .map { results =>
            assertTrue(
              results.count(_.isLeft) == 8,
              results.collect {
                case Left(PortableBundleRefusal.DigestMismatch(name, _, _)) =>
                  name
              }.toSet == Set(InputName)
            )
          }
      }
    }
  )
