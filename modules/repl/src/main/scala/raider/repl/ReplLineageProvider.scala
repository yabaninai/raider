package raider.repl

import raider.cli.lineage.LineageSnapshot
import raider.ui.{AuxTabProvider, LineageTab}
import zio.{Scope, ZIO}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

/** REPL adapter for the CLI's committed lineage snapshot protocol.
  *
  * The configured path is a run directory, never a data/source filename. All
  * reads go through LineageSnapshot.read, which resolves and validates the
  * lineage-commit.json pointer and both committed digests. A scoped poller
  * refreshes an atomic read model so the shell's pure provider never performs
  * filesystem IO or blocks editable input.
  */
private[repl] object ReplLineageProvider:

  def make(directory: Option[Path]): ZIO[Scope, Nothing, AuxTabProvider] =
    val current = new AtomicReference[LineageTab.State](
      directory match
        case Some(_) => LineageTab.State.Unavailable("no committed snapshot")
        case None =>
          LineageTab.State.Unavailable(
            "set RAIDER_LINEAGE_DIR to a run directory"
          )
    )
    val refresh: ZIO[Any, Nothing, Unit] = directory match
      case None => ZIO.unit
      case Some(dir) =>
        ZIO
          .attemptBlocking {
            LineageSnapshot.read(dir) match
              case Right(snapshot) =>
                val source = Files.readString(snapshot.sourcePath, UTF_8)
                LineageTab.State.Available(source)
              case Left(error) =>
                LineageTab.State.Invalid(String.valueOf(error))
          }
          .catchAll { error =>
            ZIO.succeed(LineageTab.State.Invalid(String.valueOf(error)))
          }
          .flatMap(state => ZIO.succeed(current.set(state)))

    val poll = (refresh *> ZIO.sleep(zio.Duration.fromMillis(200))).forever
    poll.forkScoped.as(LineageTab.provider(() => current.get()))

end ReplLineageProvider
