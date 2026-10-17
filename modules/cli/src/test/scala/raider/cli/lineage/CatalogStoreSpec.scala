package raider.cli.lineage

import zio.json.{DecoderOps, EncoderOps}
import zio.json.ast.Json
import zio.test.*
import zio.{Chunk, Ref, Scope, ZIO}

import raider.core.{JobId, RootId, SessionId}
import raider.runtime.budget.MicroUsd
import raider.runtime.events.EventCodecs.given
import raider.runtime.events.{
  EventEnvelope,
  EventPayload,
  ToolOutcome,
  UsageSnapshot
}
import raider.runtime.jobs.JobStatus
import raider.runtime.lineage.model.GraphHash
import raider.runtime.lineage.replay.GraphComparator
import raider.runtime.lineage.reduce.LineageReducer

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{
  AtomicMoveNotSupportedException,
  Files,
  Path,
  StandardCopyOption
}
import java.nio.file.attribute.FileTime

/** LIN-12 acceptance for the campaign-catalog persistence
  * (docs/tasks/lineage.md LIN-12, docs/lineage.md §5, ADR-027 §5):
  *   - LINC-01: persist+reload roundtrip is IDENTITY — records reload with the
  *     same graph-hash and verifyPartial verdicts (as full [[CatalogEntry]]
  *     values), a repeat refresh of an unchanged tree is byte-identical, and
  *     the replay CLI still works OVER the persisted catalog (both read
  *     surfaces coexist on one run directory untouched);
  *   - LINC-02: a damaged persisted catalog is a TYPED error NAMING the
  *     offending record — nothing is silently skipped, overwritten or repaired;
  *     `refresh` refuses to publish a fresh scan over damaged persisted state;
  *   - LINC-03: concurrent appends from several runs are atomic with ZERO
  *     losses and a deterministic read order; a same-key append collapses to
  *     exactly one whole record; concurrent readers only ever observe WHOLE
  *     documents.
  *
  * Fixture note: `LineageCorpus` lives in raiderRuntime's TEST scope and
  * raiderCli has no `test->test` dependency on it (build.sbt is frozen for this
  * lane), so the events.jsonl fixture below REPLICATES the semantics used by
  * [[LineageCatalogSpec]]: the same frozen `EventCodecs` (schemaVersion 1, one
  * envelope per line, trailing newline, atomic tmp+move) and the same
  * corrupted-tail trick (a strict prefix of a real line — never valid JSON).
  */
object CatalogStoreSpec extends ZIOSpecDefault:

  // ----- run fixture (LineageCatalogSpec semantics, test-local) ---------------

  /** Minimal REAL campaign for `seed`: the root job's start transition, one
    * model round, one tool call, exactly one Terminal. Reduces (strict) to a
    * 2-node graph — validated in [[expectedHash]] before any test relies on it.
    */
  private def campaign(seed: Int): Vector[EventEnvelope] =
    val rootId = RootId(s"root-$seed")
    val sessionId = SessionId(s"sess-$seed")
    val job = JobId(s"job-$seed")
    val payloads = Vector(
      EventPayload.JobStatusChanged(
        JobStatus.Queued,
        JobStatus.Running,
        None
      ), // the start transition
      EventPayload.ModelCallStarted(1, "mock-model-a", Some(12)),
      EventPayload.ModelCallFinished(
        1,
        250L + seed,
        UsageSnapshot(MicroUsd(60_000), MicroUsd(45_000), unknown = false)
      ),
      EventPayload.ToolStarted("call-1", "bash", "ls -la"),
      EventPayload.ToolFinished("call-1", "bash", ToolOutcome.Succeeded),
      EventPayload.Terminal(JobStatus.Succeeded)
    )
    payloads.zipWithIndex.map { (payload, i) =>
      EventEnvelope(
        schemaVersion = 1,
        seq = (i + 1).toLong,
        jobId = job,
        rootId = rootId,
        sessionId = sessionId,
        atMs = 1760000000000L + seed * 1000L + i * 250L,
        payload = payload
      )
    }

  /** Canonical JSONL lines of the fixture campaign (frozen wire codecs). */
  private def jsonlLines(envelopes: Vector[EventEnvelope]): Vector[String] =
    envelopes.map(_.toJson)

  /** Atomic JSONL write (tmp + ATOMIC_MOVE with a plain-move fallback). */
  private def writeJsonl(lines: Vector[String], path: Path): Path =
    Files.createDirectories(Option(path.getParent).getOrElse(Path.of(".")))
    val tmp = Files.createTempFile(
      path.getParent,
      s".${path.getFileName}",
      ".tmp"
    )
    Files.write(tmp, lines.mkString("", "\n", "\n").getBytes(UTF_8))
    try
      Files.move(
        tmp,
        path,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE
      )
    catch
      case _: AtomicMoveNotSupportedException =>
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
    finally Files.deleteIfExists(tmp)
    path

  /** Writes `runsRoot/<name>/events.jsonl` and pins its FS mtime (the catalog's
    * sort key / scannedAtMs source must be the file's mtime, not the clock).
    */
  private def writeRun(
      runsRoot: Path,
      name: String,
      seed: Int,
      mtimeMs: Long
  ): Path =
    val dir = runsRoot.resolve(name)
    writeJsonl(jsonlLines(campaign(seed)), dir.resolve("events.jsonl"))
    Files.setLastModifiedTime(
      dir.resolve("events.jsonl"),
      FileTime.fromMillis(mtimeMs)
    )
    dir

  /** Torn run: canonical lines plus ONE trailing line that is a strict PREFIX
    * of the last real line — a partial entry with a reported tail (LIN8-B-02).
    */
  private def writeTornRun(
      runsRoot: Path,
      name: String,
      seed: Int,
      mtimeMs: Long
  ): Path =
    val lines = jsonlLines(campaign(seed))
    val dir = runsRoot.resolve(name)
    writeJsonl(
      lines :+ lines.last.take(lines.last.length / 2),
      dir.resolve("events.jsonl")
    )
    Files.setLastModifiedTime(
      dir.resolve("events.jsonl"),
      FileTime.fromMillis(mtimeMs)
    )
    dir

  /** Hard-damaged run: an undecodable line BEFORE the tail — the scan refuses
    * TYPED instead of cataloging the run.
    */
  private def writeBrokenRun(
      runsRoot: Path,
      name: String,
      seed: Int,
      mtimeMs: Long
  ): Path =
    val lines = jsonlLines(campaign(seed))
    val dir = runsRoot.resolve(name)
    writeJsonl(
      lines.patch(2, Vector("{{not-json"), 0),
      dir.resolve("events.jsonl")
    )
    Files.setLastModifiedTime(
      dir.resolve("events.jsonl"),
      FileTime.fromMillis(mtimeMs)
    )
    dir

  /** The canonical hash a catalog record MUST carry for `seed` (LINC-01). */
  private def expectedHash(seed: Int): String =
    LineageReducer.reduce(campaign(seed)) match
      case Right(g) => GraphHash(g)
      case Left(err) =>
        throw new IllegalStateException(s"seed $seed does not reduce: $err")

  /** A synthetic record for store-level tests (no tree needed — the store is
    * file mechanics over records, it never re-reads run directories).
    */
  private def record(
      name: String,
      seed: Int,
      scannedAtMs: Long,
      sessionId: Option[String],
      pinned: Option[PinnedSummary] = None
  ): CatalogRecord =
    CatalogRecord(
      path = s"/campaigns/runs/$name",
      rootId = s"root-$seed",
      sessionId = sessionId,
      nodeCount = 2,
      hash = s"hash-$seed",
      complete = true,
      tail = None,
      scannedAtMs = scannedAtMs,
      pinnedEnv = pinned
    )

  private def currentPinned: PinnedSummary =
    PinnedSummary.of(EnvPinning.currentEnv())

  /** Scope-managed temp directory, deleted with its content on release. */
  private def tempDir(prefix: String): ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(
      ZIO.attempt(Files.createTempDirectory(prefix)).orDie
    )(d => ZIO.attempt(deleteRecursively(d)).orDie.ignore)

  private def deleteRecursively(root: Path): Unit =
    val stream = Files.walk(root)
    try
      stream
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .forEach(p => Files.deleteIfExists(p))
      ()
    finally stream.close()

  /** Scope-managed temp campaign root; the runs root is `<tmp>/runs`. */
  private def tempCampaign: ZIO[Scope, Nothing, Path] =
    tempDir("raider-catalog-store").map { root =>
      Files.createDirectories(root.resolve("runs"))
      root.resolve("runs")
    }

  private def bytesOf(path: Path): ZIO[Any, Nothing, Seq[Byte]] =
    ZIO.attempt(Files.readAllBytes(path).toSeq).orDie

  /** LINC-03 reader: hammer load while `done` is false. Missing is legal only
    * before the FIRST append lands — anything non-whole (Corrupt/Io) fails the
    * test loud (a torn read is a contract violation).
    */
  private def readerLoop(
      done: Ref[Boolean],
      file: Path
  ): ZIO[Any, Nothing, Int] =
    done.get.flatMap { isDone =>
      if isDone then ZIO.succeed(0)
      else
        CatalogStore.load(file) match
          case Right(_) => ZIO.yieldNow *> readerLoop(done, file).map(_ + 1)
          case Left(CatalogStoreError.Missing(_)) =>
            ZIO.yieldNow *> readerLoop(done, file)
          case Left(other) =>
            ZIO.dieMessage(s"LINC-03: torn read observed: $other")
    }

  // ----- acceptance -----------------------------------------------------------

  def spec: Spec[TestEnvironment & Scope, Any] = suite("CatalogStore")(
    suite("LINC-01 persist+reload roundtrip")(
      test(
        "refresh persists scan records; reload is identity (graph-hash, verdicts, pinned summary)"
      ) {
        ZIO.scoped {
          tempCampaign.flatMap { runsRoot =>
            ZIO.attempt {
              writeRun(runsRoot, "work-aaaa", seed = 1, mtimeMs = 1000L)
              writeTornRun(runsRoot, "work-bbbb", seed = 2, mtimeMs = 2000L)
              val file = CatalogStore.catalogFile(runsRoot)
              val scanned = LineageCatalog.scan(runsRoot).toOption.get
              val refreshed = CatalogStore.refresh(runsRoot)
              val reloaded = CatalogStore.load(file)
              val records = reloaded.toOption.get
              val torn = records.find(_.path.endsWith("work-bbbb")).get
              val good = records.find(_.path.endsWith("work-aaaa")).get
              assertTrue(
                records.length == 2,
                // the reload is the refresh, record by record
                reloaded == refreshed,
                // ...and identical to recordsOf over the plain scan
                refreshed.toOption.get == CatalogStore
                  .recordsOf(scanned, Some(currentPinned)),
                // FULL entry identity: the persisted records restore as the
                // SAME CatalogEntry values (graph-hash + verifyPartial verdict)
                records.map(_.toEntry) == scanned,
                // deterministic order: mtime desc (the torn run is newest)
                records.map(r => Path.of(r.path).getFileName.toString) ==
                  Vector("work-bbbb", "work-aaaa"),
                // verdicts survived: torn = partial with the tail reported
                !torn.complete,
                torn.tail.isDefined && torn.tail.exists(_.lineNo == 7),
                good.complete && good.tail.isEmpty,
                good.hash == expectedHash(1),
                torn.hash == expectedHash(2),
                records.forall(_.pinnedEnv == Some(currentPinned)),
                records.forall(_.sessionId.isDefined)
              )
            }
          }
        }
      },
      test("repeat refresh of an unchanged tree is byte-identical") {
        ZIO.scoped {
          tempCampaign.flatMap { runsRoot =>
            ZIO.attempt {
              writeRun(runsRoot, "work-only", seed = 3, mtimeMs = 1000L)
              val file = CatalogStore.catalogFile(runsRoot)
              CatalogStore.refresh(runsRoot)
              val bytes1 = Files.readAllBytes(file)
              val records1 = CatalogStore.load(file)
              CatalogStore.refresh(runsRoot)
              // eager reads: zio-test evaluates assertTrue arrows lazily,
              // AFTER the scope (and the temp tree) is gone
              val bytes2 = Files.readAllBytes(file)
              val records2 = CatalogStore.load(file)
              assertTrue(
                bytes1.toSeq == bytes2.toSeq,
                records2 == records1
              )
            }
          }
        }
      },
      test(
        "persisted catalog coexists with the replay CLI over the same run directory"
      ) {
        live {
          ZIO.scoped {
            for
              runsRoot <- tempCampaign
              ws <- tempDir("raider-catalog-ws")
              runDir <- ZIO.attempt {
                val dir =
                  writeRun(runsRoot, "work-replay", seed = 1, mtimeMs = 1000L)
                // Replay uses the committed data/source pair alongside the
                // catalog surface (events.jsonl) in the same run directory.
                LineageSnapshot.publish(
                  dir,
                  ReplayHarness.document.toJson.getBytes(UTF_8),
                  ReplayHarness.document.runId.getBytes(UTF_8)
                ) match
                  case Right(()) => dir
                  case Left(err) =>
                    throw new IllegalStateException(s"fixture bug: $err")
              }.orDie
              before <- bytesOf(runDir.resolve("events.jsonl"))
              _ <- ZIO.attempt(CatalogStore.refresh(runsRoot)).orDie
              after <- bytesOf(runDir.resolve("events.jsonl"))
              record <- ZIO.attempt {
                CatalogStore
                  .load(CatalogStore.catalogFile(runsRoot))
                  .toOption
                  .get
                  .find(_.path.endsWith("work-replay"))
                  .get
              }.orDie
              verdict <- ReplayCli.run(ReplayCli.Config(runDir, ws, None))
            yield verdict match
              case ReplayCli.Verdict.Match(hash, nodes) =>
                val replayHash = GraphComparator
                  .compareNormalized(
                    ReplayHarness.recordedGraph(ReplayHarness.document),
                    ReplayHarness.recordedGraph(ReplayHarness.document)
                  )
                  .toOption
                  .getOrElse("?")
                assertTrue(
                  after == before, // persist touched NOTHING inside the run dir
                  record.complete,
                  record.hash == expectedHash(
                    1
                  ), // catalog surface (verifyPartial)
                  nodes == 3,
                  hash == replayHash, // replay surface (normalized)
                  ReplayCli.exitCode(verdict) == 0
                )
              case other =>
                throw new IllegalStateException(
                  s"LINC-01: expected replay Match over the persisted " +
                    s"catalog, got $other"
                )
          }
        }
      },
      test(
        "chat==headless: ONE record shape — identical member keys with and without sessionId"
      ) {
        ZIO.scoped {
          tempCampaign.flatMap { runsRoot =>
            ZIO.attempt {
              val file = CatalogStore.catalogFile(runsRoot)
              val records = Vector(
                record(
                  "work-chat",
                  seed = 1,
                  scannedAtMs = 100L,
                  sessionId = None
                ),
                record(
                  "work-headless",
                  seed = 2,
                  scannedAtMs = 200L,
                  sessionId = Some("sess-2")
                )
              )
              CatalogStore.append(file, records)
              val text = Files.readString(file, UTF_8)
              // the document is a schemaVersion-1 OBJECT holding the records
              val memberKeys: Option[Chunk[Set[String]]] =
                ("[" + text + "]").fromJson[Json] match
                  case Right(wrapper) =>
                    wrapper match
                      case Json.Arr(values) if values.size == 1 =>
                        values.head match
                          case Json.Obj(fields) =>
                            fields.collectFirst {
                              case ("records", Json.Arr(items)) =>
                                items.map {
                                  case Json.Obj(recFields) =>
                                    recFields.map(_._1).toSet
                                  case other =>
                                    throw new IllegalStateException(
                                      s"fixture bug: $other"
                                    )
                                }
                            }
                          case other =>
                            throw new IllegalStateException(
                              s"fixture bug: $other"
                            )
                      case other =>
                        throw new IllegalStateException(s"fixture bug: $other")
                  case Left(reason) =>
                    throw new IllegalStateException(s"fixture bug: $reason")
              val reloaded = CatalogStore.load(file).toOption.get
              val chat = reloaded.find(_.path.endsWith("work-chat")).get
              val headless = reloaded.find(_.path.endsWith("work-headless")).get
              val keySets = memberKeys.get
              // the file order is mtime DESC: headless (200) first
              val chatKeys = keySets(1)
              val headlessKeys = keySets(0)
              // ONE shape for both modes: no mode/type discriminator exists —
              // the key sets differ ONLY by the optional `sessionId` member,
              // which a chat record without a session honestly omits
              assertTrue(
                keySets.length == 2,
                chatKeys ==
                  Set(
                    "path",
                    "rootId",
                    "nodeCount",
                    "hash",
                    "complete",
                    "scannedAtMs"
                  ),
                headlessKeys == chatKeys + "sessionId",
                reloaded == Vector(headless, chat),
                chat.sessionId.isEmpty, // honest absence, not invented
                headless.sessionId == Some("sess-2")
              )
            }
          }
        }
      }
    ),
    suite("LINC-02 damaged persisted catalog is typed, never silent")(
      test(
        "one damaged record among good ones — Corrupt names the record, no auto-repair"
      ) {
        ZIO.scoped {
          tempCampaign.flatMap { runsRoot =>
            ZIO.attempt {
              val file = CatalogStore.catalogFile(runsRoot)
              val good = record(
                "work-good",
                seed = 1,
                scannedAtMs = 100L,
                sessionId = Some("sess-1")
              )
              CatalogStore.append(file, Vector(good))
              // corrupt ONE record: nodeCount becomes a string (type damage),
              // the array/document grammar stays valid
              val text = Files.readString(file, UTF_8)
              val damaged = text.replace(
                s""""nodeCount":${good.nodeCount}""",
                """"nodeCount":"two""""
              )
              Files.writeString(file, damaged, UTF_8)
              val result = CatalogStore.load(file)
              val afterBytes = Files.readAllBytes(file).toSeq
              assertTrue(
                result.left.exists {
                  case CatalogStoreError.Corrupt(_, rec, detail) =>
                    rec.contains("work-good") && detail.nonEmpty
                  case _ => false
                },
                // NO auto-repair: the damaged file is left exactly as it is
                afterBytes == damaged.getBytes(UTF_8).toSeq
              )
            }
          }
        }
      },
      test(
        "garbage, truncated and wrong-schemaVersion documents — typed Corrupt at document level"
      ) {
        ZIO.scoped {
          tempCampaign.flatMap { runsRoot =>
            ZIO.attempt {
              val file = CatalogStore.catalogFile(runsRoot)
              CatalogStore.append(
                file,
                Vector(
                  record("work-a", 1, 100L, sessionId = Some("sess-1"))
                )
              )
              val whole = Files.readString(file, UTF_8)
              val cases = Vector(
                "this is not json at all", // garbage
                whole.take(whole.length - 12), // truncated tail
                "{\"schemaVersion\":2,\"records\":[]}", // unsupported schema
                "{\"records\":[]}" // missing schemaVersion
              )
              val results = cases.map { text =>
                Files.writeString(file, text, UTF_8)
                CatalogStore.load(file)
              }
              assertTrue(
                results.forall(_.left.exists {
                  case CatalogStoreError.Corrupt(_, rec, _) =>
                    rec == CatalogStore.DocumentLevel
                  case _ => false
                }),
                results(2).left.exists {
                  case CatalogStoreError.Corrupt(_, _, detail) =>
                    detail.contains("schemaVersion")
                  case _ => false
                }
              )
            }
          }
        }
      },
      test(
        "refresh over a damaged persisted catalog — typed Store refusal, NO silent overwrite"
      ) {
        ZIO.scoped {
          tempCampaign.flatMap { runsRoot =>
            ZIO.attempt {
              writeRun(runsRoot, "work-aaaa", seed = 1, mtimeMs = 1000L)
              val file = CatalogStore.catalogFile(runsRoot)
              CatalogStore.refresh(runsRoot)
              val damagedText = "corrupted beyond recognition {"
              Files.writeString(file, damagedText, UTF_8)
              val before = Files.readAllBytes(file)
              // the tree itself is FINE — the damaged persisted state still
              // refuses the whole refresh: a fresh scan is never published
              // over damaged records silently
              val result = CatalogStore.refresh(runsRoot)
              // eager reads: assertTrue arrows evaluate lazily, after the
              // scope (and the temp tree) is gone
              val afterBytes = Files.readAllBytes(file)
              assertTrue(
                result.left.exists {
                  case RefreshError.Store(
                        CatalogStoreError.Corrupt(path, _, _)
                      ) =>
                    path == file
                  case _ => false
                },
                afterBytes.toSeq == before.toSeq
              )
            }
          }
        }
      },
      test("append over a damaged catalog — typed refusal, file untouched") {
        ZIO.scoped {
          tempCampaign.flatMap { runsRoot =>
            ZIO.attempt {
              val file = CatalogStore.catalogFile(runsRoot)
              Files.writeString(file, "{ not a catalog", UTF_8)
              val before = Files.readAllBytes(file)
              val result =
                CatalogStore.append(
                  file,
                  Vector(
                    record("work-x", 1, 100L, sessionId = Some("sess-1"))
                  )
                )
              // eager reads: assertTrue arrows evaluate lazily, after the
              // scope (and the temp tree) is gone
              val afterBytes = Files.readAllBytes(file)
              assertTrue(
                result.left.exists {
                  case CatalogStoreError.Corrupt(_, _, _) => true
                  case _                                  => false
                },
                afterBytes.toSeq == before.toSeq
              )
            }
          }
        }
      },
      test(
        "refresh: scan damage refuses typed (RunUnreadable / NotFound / NotADirectory), nothing persisted"
      ) {
        ZIO.scoped {
          tempCampaign.flatMap { runsRoot =>
            ZIO.attempt {
              val file = CatalogStore.catalogFile(runsRoot)
              writeBrokenRun(runsRoot, "work-broken", seed = 1, mtimeMs = 1000L)
              val scanDamage = CatalogStore.refresh(runsRoot)
              val missing = CatalogStore.refresh(
                runsRoot.getParent.resolve("nope")
              )
              val notDir = {
                val f = runsRoot.getParent.resolve("a-file")
                Files.writeString(f, "not a directory", UTF_8)
                CatalogStore.refresh(f)
              }
              // eager probe: assertTrue arrows evaluate lazily, after the
              // scope (and the temp tree) is gone
              val catalogMissing = !Files.exists(file)
              assertTrue(
                scanDamage.left.exists {
                  case RefreshError.Scan(
                        CatalogError.RunUnreadable(_, detail)
                      ) =>
                    detail.contains("MalformedLine")
                  case _ => false
                },
                missing.left.exists {
                  case RefreshError.Scan(CatalogError.NotFound(_)) => true
                  case _                                           => false
                },
                notDir.left.exists {
                  case RefreshError.Scan(CatalogError.NotADirectory(_)) => true
                  case _                                                => false
                },
                catalogMissing // refused scans persist nothing
              )
            }
          }
        }
      },
      test("load: absent catalog is typed Missing; a directory is typed Io") {
        ZIO.scoped {
          tempCampaign.flatMap { runsRoot =>
            ZIO.attempt {
              val file = CatalogStore.catalogFile(runsRoot)
              val missing = CatalogStore.load(file)
              Files.createDirectories(file) // a directory AT the catalog path
              val io = CatalogStore.load(file)
              assertTrue(
                missing == Left(CatalogStoreError.Missing(file)),
                io.left.exists {
                  case CatalogStoreError.Io(_, _) => true
                  case _                          => false
                }
              )
            }
          }
        }
      }
    ),
    suite("LINC-03 concurrent appends — atomic, 0 losses, deterministic")(
      test(
        "8 writers x 5 records — all 40 land exactly once, deterministic read order (3 repeats)"
      ) {
        ZIO.scoped {
          tempCampaign.flatMap { runsRoot =>
            val file = CatalogStore.catalogFile(runsRoot)
            val writerRecords: Vector[Vector[CatalogRecord]] =
              (1 to 8).toVector.map { w =>
                (1 to 5).toVector.map { r =>
                  record(
                    name = s"work-w$w-r$r",
                    seed = w * 10 + r,
                    scannedAtMs = r * 100L + w,
                    sessionId = Some(s"sess-${w * 10 + r}")
                  )
                }
              }
            // the expected read order is computable BEFORE the race runs:
            // distinct mtimes and distinct names, catalog sort (mtime desc,
            // path asc)
            val expected =
              writerRecords.flatten.sortBy(r => (-r.scannedAtMs, r.path))
            def oneRound: ZIO[Any, CatalogStoreError, TestResult] =
              ZIO
                .foreachPar(writerRecords) { recs =>
                  ZIO.foreach(recs) { r =>
                    ZIO.fromEither(CatalogStore.append(file, Vector(r)))
                  }
                }
                .flatMap { _ =>
                  ZIO.attempt {
                    val finalRecords = CatalogStore.load(file)
                    assertTrue(
                      finalRecords == Right(expected), // 0 losses, 0 duplicates
                      finalRecords.toOption.get
                        .map(_.path)
                        .distinct
                        .length == 40
                    )
                  }.orDie
                }
            ZIO
              .foreach((1 to 3).toList) { _ =>
                ZIO.attempt(Files.deleteIfExists(file)).orDie *> oneRound
              }
              .map(rs => assertTrue(rs.forall(_.isSuccess)))
          }
        }
      },
      test(
        "same run-dir key from 4 writers — exactly ONE whole record lands (no mixing)"
      ) {
        ZIO.scoped {
          tempCampaign.flatMap { runsRoot =>
            val file = CatalogStore.catalogFile(runsRoot)
            val variants = (1 to 4).toVector.map { w =>
              record(
                "work-same",
                seed = w,
                scannedAtMs = 100L + w,
                sessionId = Some(s"sess-$w")
              )
            }
            ZIO
              .foreachPar(variants.toList) { v =>
                ZIO.fromEither(CatalogStore.append(file, Vector(v)))
              }
              .flatMap { _ =>
                ZIO.attempt {
                  val single = CatalogStore
                    .load(file)
                    .toOption
                    .get
                    .filter(_.path.endsWith("work-same"))
                  assertTrue(
                    single.length == 1, // upsert: last complete write wins
                    variants.exists(
                      _ == single.head
                    ) // WHOLE variant, never a mix
                  )
                }.orDie
              }
          }
        }
      },
      test(
        "concurrent reads during appends only ever see WHOLE documents"
      ) {
        ZIO.scoped {
          tempCampaign.flatMap { runsRoot =>
            for
              file <- ZIO.succeed(CatalogStore.catalogFile(runsRoot))
              done <- Ref.make(false)
              records = (1 to 30).toVector.map(i =>
                record(
                  s"work-live-$i",
                  i,
                  i * 10L,
                  sessionId = Some(s"sess-$i")
                )
              )
              writer <- (ZIO
                .foreach(records) { r =>
                  ZIO.fromEither(CatalogStore.append(file, Vector(r))) <*
                    ZIO.yieldNow
                }
                *> done.set(true)).fork
              rounds <- readerLoop(done, file)
              _ <- writer.join
              finalLoad <- ZIO.fromEither(CatalogStore.load(file))
            yield
              // the store's canonical read order (mtime DESC, path ASC) —
              // deterministic regardless of the append interleaving
              val expected = records.sortBy(r => (-r.scannedAtMs, r.path))
              assertTrue(
                finalLoad == expected, // every append landed, ordered
                rounds >= 0
              )
          }
        }
      },
      test("append of an empty batch is a no-op (no file created)") {
        ZIO.scoped {
          tempCampaign.flatMap { runsRoot =>
            ZIO.attempt {
              val file = CatalogStore.catalogFile(runsRoot)
              val result = CatalogStore.append(file, Vector.empty)
              assertTrue(
                result == Right(()),
                !Files.exists(file) // no invented empty catalog on disk
              )
            }
          }
        }
      }
    )
  )

end CatalogStoreSpec
