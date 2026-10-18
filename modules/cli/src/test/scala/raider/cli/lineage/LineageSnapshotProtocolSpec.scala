package raider.cli.lineage

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import zio.test.*
import zio.ZIO

object LineageSnapshotProtocolSpec extends ZIOSpecDefault:

  private def fixture: Array[Byte] =
    val path = java.nio.file.Path
      .of("contracts/lineage/golden/tiny-graph/lineage-data.json")
    Files.readAllBytes(path)

  def spec = suite("lineage snapshot commit protocol")(
    test("LIN3B-02: readers receive typed Missing before the initial commit") {
      val dir = Files.createTempDirectory("lineage-commit-not-yet-published")
      val missing = LineageSnapshot.read(dir)
      assertTrue(
        missing == Left(
          LineageSnapshot.Error.Missing(dir.resolve(LineageSnapshot.CommitName))
        )
      )
    },
    test(
      "LIN3B-02: missing, malformed, and incomplete commit pointers fail explicitly"
    ) {
      val dir = Files.createTempDirectory("lineage-commit-invalid")
      Files.writeString(
        dir.resolve(LineageSnapshot.CommitName),
        "broken\n",
        UTF_8
      )
      val malformed = LineageSnapshot.read(dir)
      Files.delete(dir.resolve(LineageSnapshot.CommitName))
      val missing = LineageSnapshot.read(dir)
      assertTrue(malformed.isLeft, missing.isLeft)
    },
    test(
      "LIN3B-01/03: eight readers observe only committed complete pairs, three repetitions"
    ) {
      val base = fixture
      val pool = Executors.newFixedThreadPool(9)
      var partial = 0
      try
        (1 to 3).foreach { repetition =>
          val dir = Files.createTempDirectory(s"lineage-commit-$repetition")
          val initial = new String(base, UTF_8)
          val seeded = LineageSnapshot.publish(
            dir,
            base,
            initial.getBytes(UTF_8)
          )
          if seeded.isLeft then
            throw new AssertionError(
              "could not seed initial committed snapshot: " + seeded
            )
          val go = new CountDownLatch(1)
          val done = new CountDownLatch(1)
          val writer = pool.submit(
            new Runnable:
              def run(): Unit =
                go.await()
                (1 to 120).foreach { i =>
                  val payload = new String(base, UTF_8)
                    .replace("run-tiny-001", f"run-tiny-$i%03d")
                  LineageSnapshot.publish(
                    dir,
                    payload.getBytes(UTF_8),
                    payload.getBytes(UTF_8)
                  ) match
                    case Left(_) => partial += 1
                    case _       => ()
                }
                done.countDown()
          )
          val readers = (1 to 8).map { _ =>
            pool.submit(
              new Runnable:
                def run(): Unit =
                  go.await()
                  while !done.await(1, TimeUnit.MILLISECONDS) do observe(dir)
                  observe(dir)
            )
          }
          go.countDown()
          writer.get(60, TimeUnit.SECONDS)
          readers.foreach(_.get(60, TimeUnit.SECONDS))
        }
        assertTrue(partial == 0)
      finally pool.shutdownNow()
    },
    test(
      "LIN3-03: interruption after generation writes preserves the old pointer until a complete publication"
    ) {
      val dir = Files.createTempDirectory("lineage-commit-crash-window")
      val oldData = fixture
      val oldSource = "old committed source".getBytes(UTF_8)
      val newData = fixture
      val newSource = "new complete source".getBytes(UTF_8)
      val seeded = LineageSnapshot.publish(dir, oldData, oldSource)
      if seeded.isLeft then
        throw new AssertionError("could not seed old snapshot: " + seeded)
      val old = LineageSnapshot
        .read(dir)
        .fold(
          error => throw new AssertionError("old pointer unreadable: " + error),
          identity
        )
      val atWindow = new CountDownLatch(1)
      val neverRelease = new CountDownLatch(1)
      val publication = ZIO.attemptBlockingInterrupt {
        LineageSnapshot.publishAfterGeneration(dir, newData, newSource) {
          atWindow.countDown()
          neverRelease.await()
        }
      }.fork
      for
        fiber <- publication
        reached <- ZIO
          .attemptBlocking(atWindow.await(10, TimeUnit.SECONDS))
          .orDie
        _ <- fiber.interrupt
        during = LineageSnapshot.read(dir)
        remaining = Files.list(dir)
        generated =
          try
            remaining.toArray
              .map(_.asInstanceOf[java.nio.file.Path])
              .filter(p =>
                p.getFileName.toString.startsWith("lineage-") &&
                  p.getFileName.toString != LineageSnapshot.CommitName
              )
              .toVector
          finally remaining.close()
        generationReadable = generated.exists(p =>
          Files.isReadable(p) && Files.readAllBytes(p).sameElements(newData)
        ) && generated.exists(p =>
          Files.isReadable(p) && Files.readAllBytes(p).sameElements(newSource)
        )
        _ <- ZIO.unit
        completed = LineageSnapshot.publish(dir, newData, newSource)
        after = LineageSnapshot.read(dir)
      yield assertTrue(
        reached,
        during.isRight,
        during.toOption.exists(_.generation == old.generation),
        during.toOption.exists(s =>
          Files.isReadable(s.dataPath) && Files.isReadable(s.sourcePath)
        ),
        generationReadable,
        completed.isRight,
        after.isRight,
        after.toOption.exists(_.generation != old.generation),
        after.toOption.exists(s =>
          Files.readAllBytes(s.dataPath).sameElements(newData) &&
            Files.readAllBytes(s.sourcePath).sameElements(newSource)
        )
      )
    }
  )

  private def observe(dir: java.nio.file.Path): Unit =
    LineageSnapshot.read(dir) match
      case Right(snapshot) =>
        val source = Files.readString(snapshot.sourcePath, UTF_8)
        if !source.contains(snapshot.document.runId) then
          throw new AssertionError("committed pair generation mismatch")
      case Left(LineageSnapshot.Error.Missing(_)) =>
        throw new AssertionError(
          "commit pointer disappeared during replacement"
        )
      case Left(error) =>
        throw new AssertionError("invalid committed snapshot: " + error)

end LineageSnapshotProtocolSpec
