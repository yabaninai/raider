package raider.cli.lineage

import zio.test.*
import zio.{Scope, ZIO}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** LIN-06C acceptance (packet L52, ADR-027 §2 "live re-run: isolated workspace
  * copy, budget, diff report"): the workspace isolation mechanics are pinned
  * end-to-end on real temp-dir fixtures — no mocks, no network:
  *   - LIN6-C-01 (MAIN): `isolate` copies the workspace byte-for-byte into a
  *     unique target under the parent; mutations INJECTED INTO THE COPY
  *     (rewrite + delete + add) leave the original untouched —
  *     `verifyUntouched` against the pre-run snapshot returns Right;
  *   - LIN6-C-02: tampering the ORIGINAL after the snapshot is reported typed
  *     as `Tampered(file)` (rewrite; delete; added file; deterministic
  *     offender);
  *   - typed errors: a missing original fails `isolate`/`verifyUntouched` as
  *     `OriginalMissing`;
  *   - FW1-01/FW1-02 (review F-W22R1-1): `verifyUntouched` writes NOTHING to
  *     stdout — captured `System.out` is empty on both the success and the
  *     `Tampered` call, and the typed detail carries no absolute paths.
  *
  * Snapshot keys are workspace-RELATIVE paths (see [[WorkspaceIsolation]]), so
  * the original's snapshot is directly comparable against the copy's.
  *
  * NOTE on zio-test semantics: the `assertTrue` clause expressions are
  * evaluated when the framework renders the TestResult, i.e. AFTER the
  * `ZIO.scoped` finalizers have deleted the fixture dirs. Every filesystem
  * observation is therefore hoisted into a for-comprehension binding (evaluated
  * inside the scope) and the clauses compare computed values only.
  */
object WorkspaceIsolationSpec extends ZIOSpecDefault:

  private def freshDir(prefix: String): ZIO[Scope, Nothing, Path] =
    ZIO.acquireRelease(
      ZIO.attempt(Files.createTempDirectory(prefix)).orDie
    )(dir => ZIO.attempt(deleteRecursively(dir)).orDie.ignore)

  private def deleteRecursively(root: Path): Unit =
    val stream = Files.walk(root)
    try
      stream
        .sorted(java.util.Comparator.reverseOrder[Path]())
        .forEach(p => Files.deleteIfExists(p))
      ()
    finally stream.close()

  /** Fixture tree: two nested levels, an empty directory, distinct file
    * contents — enough to pin recursive copy + byte comparison.
    */
  private def writeTree(ws: Path): Unit =
    Files.createDirectories(ws.resolve("sub/deep"))
    Files.createDirectories(ws.resolve("empty"))
    Files.writeString(ws.resolve("a.txt"), "alpha\n", UTF_8)
    Files.writeString(ws.resolve("sub/b.txt"), "beta\n", UTF_8)
    Files.writeString(ws.resolve("sub/deep/c.txt"), "gamma\n", UTF_8)

  /** Byte-aware snapshot equality ([[Array]] `==` is reference equality). */
  private def sameSnapshot(
      a: Map[Path, Array[Byte]],
      b: Map[Path, Array[Byte]]
  ): Boolean =
    a.keySet == b.keySet && a.forall { (p, bytes) =>
      b.get(p).exists(other => java.util.Arrays.equals(bytes, other))
    }

  /** Regression harness for review F-W22R1-1 (acceptance FW1-01/FW1-02):
    * captures `System.out` around a `verifyUntouched` call. `System.out` is
    * ALWAYS restored — even if `run` throws — so a failure here can never leave
    * the JVM-level stream redirected for the rest of the test run.
    */
  private def captureStdoutAround[A](run: () => A): (String, A) =
    val originalOut = System.out
    val captured = new java.io.ByteArrayOutputStream
    try
      System.setOut(java.io.PrintStream(captured, true, UTF_8))
      val result = run()
      (captured.toString(UTF_8), result)
    finally System.setOut(originalOut)

  def spec: Spec[TestEnvironment & Scope, Any] = suite("WorkspaceIsolation")(
    test(
      "LIN6-C-01: isolate copies byte-for-byte into a unique target; mutations in the COPY leave the original untouched"
    ) {
      ZIO.scoped {
        for
          original <- freshDir("raider-l52-orig")
          parent <- freshDir("raider-l52-parent")
          _ <- ZIO.attempt(writeTree(original))
          snap <- ZIO.attempt(WorkspaceIsolation.snapshot(original))
          copy <- ZIO.fromEither(WorkspaceIsolation.isolate(original, parent))
          copySnap <- ZIO.attempt(WorkspaceIsolation.snapshot(copy))
          copy2 <- ZIO.fromEither(WorkspaceIsolation.isolate(original, parent))
          _ <- ZIO.attempt {
            // inject mutations into THE COPY only: rewrite, delete, add
            Files.writeString(copy.resolve("a.txt"), "MUTATED\n", UTF_8)
            Files.delete(copy.resolve("sub/b.txt"))
            Files.writeString(copy.resolve("sub/added.txt"), "new\n", UTF_8)
          }
          verdict = WorkspaceIsolation.verifyUntouched(original, snap)
          copyIsDirectory = Files.isDirectory(copy)
          copyEmptyPreserved = Files.isDirectory(copy.resolve("empty"))
        yield assertTrue(
          copyIsDirectory,
          copy != original,
          copy.getParent == parent,
          copy2 != copy, // a unique target per isolate call
          sameSnapshot(snap, copySnap),
          copyEmptyPreserved, // empty dirs survive
          verdict == Right(())
        )
      }
    },
    test(
      "LIN6-C-02: tampering the ORIGINAL after the snapshot is reported typed as Tampered(file)"
    ) {
      ZIO.scoped {
        for
          original <- freshDir("raider-l52-tamper")
          _ <- ZIO.attempt(writeTree(original))
          snap <- ZIO.attempt(WorkspaceIsolation.snapshot(original))
          _ <- ZIO.attempt(
            Files.writeString(
              original.resolve("sub/b.txt"),
              "TAMPERED\n",
              UTF_8
            )
          )
          verdict = WorkspaceIsolation.verifyUntouched(original, snap)
        yield verdict match
          case Left(IsolationError.Tampered(file)) =>
            assertTrue(file == Path.of("sub", "b.txt"))
          case other =>
            throw new IllegalStateException(s"expected Tampered, got $other")
      }
    },
    test(
      "LIN6-C-02b: a deleted original file and an added file are Tampered offenders too"
    ) {
      ZIO.scoped {
        for
          deleted <- freshDir("raider-l52-del")
          added <- freshDir("raider-l52-add")
          _ <- ZIO.attempt(writeTree(deleted))
          _ <- ZIO.attempt(writeTree(added))
          snapDeleted <- ZIO.attempt(WorkspaceIsolation.snapshot(deleted))
          snapAdded <- ZIO.attempt(WorkspaceIsolation.snapshot(added))
          _ <- ZIO.attempt(Files.delete(deleted.resolve("a.txt")))
          _ <- ZIO.attempt(
            Files.writeString(added.resolve("new.txt"), "x\n", UTF_8)
          )
          verdictDeleted = WorkspaceIsolation.verifyUntouched(
            deleted,
            snapDeleted
          )
          verdictAdded = WorkspaceIsolation.verifyUntouched(added, snapAdded)
        yield assertTrue(
          verdictDeleted == Left(IsolationError.Tampered(Path.of("a.txt"))),
          verdictAdded == Left(IsolationError.Tampered(Path.of("new.txt")))
        )
      }
    },
    test(
      "typed errors: a missing original fails isolate/verifyUntouched as OriginalMissing"
    ) {
      ZIO.scoped {
        for
          parent <- freshDir("raider-l52-missing")
          missing = parent.resolve("no-such-workspace")
          isolateVerdict = WorkspaceIsolation.isolate(missing, parent)
          verifyVerdict = WorkspaceIsolation.verifyUntouched(
            missing,
            Map.empty
          )
        yield assertTrue(
          isolateVerdict == Left(IsolationError.OriginalMissing(missing)),
          verifyVerdict == Left(IsolationError.OriginalMissing(missing))
        )
      }
    },
    test(
      "FW1-01/FW1-02: verifyUntouched prints NOTHING to stdout — no DEBUG lines, no absolute worktree/tmp paths (success and Tampered cases)"
    ) {
      ZIO.scoped {
        for
          original <- freshDir("raider-r1fix-clean")
          tampered <- freshDir("raider-r1fix-tampered")
          _ <- ZIO.attempt(writeTree(original))
          _ <- ZIO.attempt(writeTree(tampered))
          cleanSnap <- ZIO.attempt(WorkspaceIsolation.snapshot(original))
          tamperedSnap <- ZIO.attempt(WorkspaceIsolation.snapshot(tampered))
          _ <- ZIO.attempt(
            Files.writeString(
              tampered.resolve("sub/b.txt"),
              "TAMPERED\n",
              UTF_8
            )
          )
          (cleanOut, cleanVerdict) <- ZIO.attempt(
            captureStdoutAround(() =>
              WorkspaceIsolation.verifyUntouched(original, cleanSnap)
            )
          )
          (tamperedOut, tamperedVerdict) <- ZIO.attempt(
            captureStdoutAround(() =>
              WorkspaceIsolation.verifyUntouched(tampered, tamperedSnap)
            )
          )
          // FW1-02: the typed detail carries the workspace-RELATIVE offender
          // only — the absolute fixture paths must not appear anywhere.
          tamperedDetail = tamperedVerdict.fold(_.toString, _ => "")
          cleanDetail = cleanVerdict.fold(_.toString, _ => "")
        yield assertTrue(
          cleanVerdict == Right(()),
          tamperedVerdict == Left(
            IsolationError.Tampered(Path.of("sub", "b.txt"))
          ),
          cleanOut.isEmpty,
          tamperedOut.isEmpty,
          !cleanDetail.contains(original.toString),
          !tamperedDetail.contains(tampered.toString),
          !tamperedDetail.contains(original.toString)
        )
      }
    }
  )

end WorkspaceIsolationSpec
