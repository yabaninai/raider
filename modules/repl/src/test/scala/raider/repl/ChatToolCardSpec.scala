package raider.repl

import java.util.concurrent.ConcurrentLinkedQueue
import zio.test.*

object ChatToolCardSpec extends ZIOSpecDefault:

  def spec =
    test("tool cards render bounded arguments and the typed outcome") {
      val frames = new ConcurrentLinkedQueue[String]()
      val sink = new ChatLayoutSink(frames.add(_))
      sink.beginUser("inspect the project")
      sink.beginAssistant()
      zio.Unsafe.unsafe { implicit unsafe =>
        zio.Runtime.default.unsafe
          .run(
            sink.toolCallCard("fs_read", "{\"path\":\"src/Main.scala\"}", None)
          )
          .getOrThrow()
        zio.Runtime.default.unsafe
          .run(
            sink.toolCallCard(
              "fs_read",
              "{\"path\":\"src/Main.scala\"}",
              Some("✓ read 42 lines")
            )
          )
          .getOrThrow()
      }
      val transcript = frames.toArray.mkString("\n")
      assertTrue(
        transcript.contains("tool card | fs_read"),
        transcript.contains("src/Main.scala"),
        transcript.contains("outcome: running"),
        transcript.contains("outcome: ✓ read 42 lines")
      )
    }

end ChatToolCardSpec
