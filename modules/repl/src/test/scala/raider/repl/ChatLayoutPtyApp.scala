package raider.repl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** Test-scope launcher that can resize its own controlling PTY on driver
  * request.
  */
object ChatLayoutPtyApp:

  def main(args: Array[String]): Unit =
    val request = Paths.get(sys.env("RAI188_RESIZE_REQUEST"))
    val watcher = new Thread(() => resizeLoop(request))
    watcher.setDaemon(true)
    watcher.start()
    Main.main(Array("--chat"))

  private def resizeLoop(request: Path): Unit =
    while true do
      if Files.exists(request) then
        try
          val dimensions = Files
            .readString(request, StandardCharsets.UTF_8)
            .trim
            .split("\\s+")
          if dimensions.length == 2 then
            val rows = dimensions(0)
            val columns = dimensions(1)
            val exitCode =
              new ProcessBuilder("stty", "rows", rows, "cols", columns)
                .inheritIO()
                .start()
                .waitFor()
            Files.writeString(resultFor(request), exitCode.toString)
            Files.deleteIfExists(request)
        catch case _: Exception => ()
      Thread.sleep(20)

  private def resultFor(request: Path): Path =
    request.resolveSibling("resize.result")

end ChatLayoutPtyApp
