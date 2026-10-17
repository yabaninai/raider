package raider.cli.lineage

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import zio.test.*

object LineageSnapshotProtocolSpec extends ZIOSpecDefault:

  private def fixture: Array[Byte] =
    val path = java.nio.file.Path
      .of("contracts/lineage/golden/tiny-graph/lineage-data.json")
    Files.readAllBytes(path)

  def spec = suite("lineage snapshot commit protocol")(
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
    }
  )

  private def observe(dir: java.nio.file.Path): Unit =
    LineageSnapshot.read(dir) match
      case Right(snapshot) =>
        val source = Files.readString(snapshot.sourcePath, UTF_8)
        if !source.contains(snapshot.document.runId) then
          throw new AssertionError("committed pair generation mismatch")
      case Left(LineageSnapshot.Error.Missing(_)) => ()
      case Left(error) =>
        throw new AssertionError("invalid committed snapshot: " + error)

end LineageSnapshotProtocolSpec
