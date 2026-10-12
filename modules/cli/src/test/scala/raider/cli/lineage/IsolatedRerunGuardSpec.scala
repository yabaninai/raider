package raider.cli.lineage

import raider.dsl.lineage.data.LineageDataDocument

import scala.jdk.CollectionConverters.*

import zio.test.*
import zio.{Scope, ZIO}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** RAI-120 (review finding LIN-15, ADR-027 §2 isolation policy): the
  * [[IsolatedRerun.requireIsolatedTarget]] guard must compare the replay target
  * against the original CANONICALLY, not syntactically. A syntactic `Path`
  * equality misses aliases of the original — a mutating replay aimed at
  * `original.resolve("..").resolve(original.getFileName)` (or at a symlink to
  * the original) would pass the byte check as a «faithful copy» and execute
  * IN-PLACE:
  *   - ISO-01: the reviewer's alias reproducer is refused TYPED
  *     ([[RerunError.MutatingUnisolated]]) BEFORE any execution — the original
  *     bytes stay unchanged and the guard creates nothing under `parent`;
  *   - ISO-02: a symlink alias of the original is refused typed too; a MISSING
  *     target gets the honest typed refusal (never a silent pass);
  *   - ISO-03: a legitimate isolated copy ([[WorkspaceIsolation.isolate]]
  *     output — a DIFFERENT directory with equal bytes) still passes the guard.
  *
  * No network, no paid calls: the only "model" is the record itself; the only
  * IO is a bounded read inside fixture-sized workspaces.
  */
object IsolatedRerunGuardSpec extends ZIOSpecDefault:

  /** The dirt file the scripted mutating round creates (same record convention
    * as IsolatedRerunSpec IR-02 / LiveRerunScriptedAcceptanceSpec LIN6-02-SC:
    * the recorded RESPONSE carries an `fs_edit` `tool_calls` envelope; empty
    * `expected_sha256` = create). The data annotation deliberately stays stale
    * (fs_read/ReadOnly) — the guard must classify from the RESPONSE.
    */
  private val DirtyFile = "dirty-after-isolate.txt"

  private val DirtyRound1Response =
    """{"tool_calls":[{"id":"call-1","name":"fs_edit",""" +
      """"arguments":{"path":"dirty-after-isolate.txt","content":"dirt","expected_sha256":""}}]}"""

  private def dirtyDocument: LineageDataDocument =
    ReplayHarness.withResponse(
      ReplayHarness.document,
      ReplayHarness.Round1Ref,
      DirtyRound1Response
    )

  /** Isolated fixture workspace: fresh temp dir + the fixture file, deleted
    * after the use.
    */
  private def freshWorkspace: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val ws = Files.createTempDirectory("raider-rai120-ws")
        Files.writeString(
          ws.resolve(ReplayHarness.FixtureFile),
          ReplayHarness.FixtureFileContent,
          UTF_8
        )
        ws
      }.orDie
    )(ws => ZIO.attempt(deleteRecursively(ws)).orDie.ignore)

  /** Fresh parent directory (the guard must create NOTHING under it). */
  private def freshParent: ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(
      ZIO.attempt(Files.createTempDirectory("raider-rai120-parent")).orDie
    )(p => ZIO.attempt(deleteRecursively(p)).orDie.ignore)

  /** Pre-run byte snapshot of a workspace (the external evidence baseline). */
  private def snapshotOf(ws: Path): ZIO[Any, Nothing, Map[Path, Array[Byte]]] =
    ZIO.attempt(WorkspaceIsolation.snapshot(ws)).orDie

  private def verifyUntouched(
      ws: Path,
      before: Map[Path, Array[Byte]]
  ): ZIO[Any, Nothing, Either[IsolationError, Unit]] =
    ZIO.attempt(WorkspaceIsolation.verifyUntouched(ws, before)).orDie

  /** Directories directly under `parent`, sorted (fixture observation). */
  private def listDirs(parent: Path): Vector[Path] =
    val stream = Files.list(parent)
    try
      stream
        .iterator()
        .asScala
        .toVector
        .filter(Files.isDirectory(_))
        .sortBy(_.toString)
    finally stream.close()

  private def deleteRecursively(root: Path): Unit =
    val stream = Files.walk(root)
    try
      stream
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .forEach(p => Files.deleteIfExists(p))
      ()
    finally stream.close()

  def spec: Spec[TestEnvironment & Scope, Any] = suite("IsolatedRerunGuard")(
    test(
      "ISO-01: a syntactic alias of the original is refused typed BEFORE any execution (bytes unchanged, parent clean)"
    ) {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            parent <- freshParent
            before <- snapshotOf(ws)
            // the reviewer's reproducer: a DIFFERENT Path that denotes the
            // SAME directory — syntactic equality misses it
            alias = ws.resolve("..").resolve(ws.getFileName)
            verdict <- ZIO
              .attempt(
                IsolatedRerun.requireIsolatedTarget(dirtyDocument, ws, alias)
              )
              .orDie
            // the refusal happened BEFORE any execution: the original is
            // byte-identical and the guard created nothing under parent
            untouched <- verifyUntouched(ws, before)
            noDirtInParent <- ZIO.attempt {
              listDirs(parent).isEmpty && !Files.exists(
                parent.resolve(DirtyFile)
              )
            }.orDie
          yield assertTrue(
            verdict == Left(
              RerunError.MutatingUnisolated(Vector("fs_edit"))
            ),
            untouched == Right(()),
            noDirtInParent
          )
        }
      }
    },
    test(
      "ISO-02: a symlink alias of the original is refused typed; a missing target gets the honest typed refusal"
    ) {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            parent <- freshParent
            before <- snapshotOf(ws)
            // a symlink INSIDE parent pointing at the original workspace —
            // another canonical alias the syntactic check misses
            link = parent.resolve("ws-alias-link")
            _ <- ZIO
              .attempt(Files.createSymbolicLink(link, ws.toAbsolutePath))
              .orDie
            symlinkVerdict <- ZIO
              .attempt(
                IsolatedRerun.requireIsolatedTarget(dirtyDocument, ws, link)
              )
              .orDie
            // a MISSING target is not a faithful copy either: the honest
            // typed refusal, never a silent pass
            missing = parent.resolve("no-such-target")
            missingVerdict <- ZIO
              .attempt(
                IsolatedRerun.requireIsolatedTarget(dirtyDocument, ws, missing)
              )
              .orDie
            untouched <- verifyUntouched(ws, before)
          yield assertTrue(
            symlinkVerdict == Left(
              RerunError.MutatingUnisolated(Vector("fs_edit"))
            ),
            missingVerdict == Left(
              RerunError.MutatingUnisolated(Vector("fs_edit"))
            ),
            untouched == Right(())
          )
        }
      }
    },
    test(
      "ISO-03: a legitimate isolated copy (different directory, equal bytes) passes the guard"
    ) {
      live {
        ZIO.scoped {
          for
            ws <- freshWorkspace
            parent <- freshParent
            copy <- ZIO.attempt {
              WorkspaceIsolation.isolate(ws, parent) match
                case Right(p) => p
                case Left(error) =>
                  throw new IllegalStateException(
                    s"fixture bug: isolate failed: $error"
                  )
            }.orDie
            verdict <- ZIO
              .attempt(
                IsolatedRerun.requireIsolatedTarget(dirtyDocument, ws, copy)
              )
              .orDie
          yield assertTrue(
            copy != ws,
            verdict == Right(())
          )
        }
      }
    }
  )

end IsolatedRerunGuardSpec
