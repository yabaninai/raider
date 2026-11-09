package raider.runtime.conversation

import raider.core.{RaiderError, RequestMessage, RootId, SessionId}
import zio.ZIO
import zio.json.{DecoderOps, DeriveJsonDecoder, EncoderOps, JsonDecoder}
import zio.json.ast.Json
import zio.test._

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** Contract pinning for the session archive (RAI-036, SESSION-01/02): the
  * on-disk encoding is a CONTRACT — `contracts/sessions/expected/archive-golden.json`
  * pins the exact bytes `encodeDocument` may emit, and `archive-rejected.json`
  * pins the typed refusals for broken documents. The store mirrors the CLI
  * session-store trust discipline (strict ids, typed missing-vs-corrupt,
  * listing that never hides healthy files).
  *
  * Deterministic: temp dirs, no network, no live clock in assertions.
  */
object ConversationArchiveSpec extends ZIOSpecDefault:

  private def repoFile(rel: String*): Either[String, Path] =
    def under(root: Path): Path = rel.foldLeft(root)((b, s) => b.resolve(s))
    sys.env.get("RAIDER_REPO_ROOT").filter(_.nonEmpty) match
      case Some(root) =>
        val p = under(Path.of(root))
        if Files.exists(p) then Right(p)
        else Left(s"contracts file missing at $p")
      case None =>
        var dir = Option(Path.of("").toAbsolutePath)
        var found: Option[Path] = None
        while dir.isDefined && found.isEmpty do
          val candidate = under(dir.get)
          if Files.exists(candidate) then found = Some(candidate)
          else dir = Option(dir.get.getParent)
        found.toRight(
          "repo root not found from the test working directory; " +
            "run via run-tests.sh or set RAIDER_REPO_ROOT"
        )

  private def readPinned(path: Path): String =
    new String(Files.readAllBytes(path), UTF_8)

  private final case class RejectedCase(
      name: String,
      reasonPrefix: String,
      document: Json
  )
  private given JsonDecoder[RejectedCase] = DeriveJsonDecoder.gen

  /** The archive the golden document pins (values chosen once and frozen in
    * `contracts/sessions/expected/`; changing the encoding or these fixtures
    * requires re-pin review, never a silent edit).
    */
  private val goldenArchive: Either[RaiderError, ConversationArchive] =
    ConversationArchive.fromSnapshot(
      sessionId = SessionId("conv-demo-golden"),
      rootId = RootId("conversation_7"),
      label = Some("storage study"),
      model = "scripted",
      history = List(
        RequestMessage(Conversation.UserRole, "Study storage options"),
        RequestMessage(Conversation.AssistantRole, "Option A wins"),
        RequestMessage(Conversation.UserRole, "Compare the two best options"),
        RequestMessage(Conversation.AssistantRole, "A beats B on durability")
      ),
      archivedAtMs = 1762400000000L
    )

  private def mkArchive(id: String): Either[RaiderError, ConversationArchive] =
    ConversationArchive.fromSnapshot(
      sessionId = SessionId(id),
      rootId = RootId("conversation_1"),
      label = None,
      model = "scripted",
      history = List(
        RequestMessage(Conversation.UserRole, "prompt"),
        RequestMessage(Conversation.AssistantRole, "reply")
      ),
      archivedAtMs = 0L
    )

  private def withTempDir[A](
      f: Path => ZIO[Any, Throwable, A]
  ): ZIO[Any, Throwable, A] =
    ZIO.acquireReleaseWith(
      ZIO.attempt(Files.createTempDirectory("raider-conv-store"))
    )(p =>
      ZIO
        .attempt {
          import scala.jdk.CollectionConverters.*
          Files
            .walk(p)
            .sorted(java.util.Comparator.reverseOrder[Path]())
            .iterator()
            .asScala
            .foreach(Files.delete(_))
        }
        .orDie
    )(f)

  def spec = suite("ConversationArchive codec and store (RAI-036 SESSION-01/02)")(

    test("encodeDocument matches the pinned golden document byte for byte") {
      for
        path <- ZIO.fromEither(repoFile("contracts", "sessions", "expected", "archive-golden.json"))
        pinned = readPinned(path)
        archive <- ZIO.fromEither(goldenArchive)
        encoded = ConversationArchive.encodeDocument(archive)
        // The pinned document is one compact line; a single trailing newline
        // is tolerated (editor artifacts), anything else is a contract break.
        pinnedCore = pinned.stripLineEnd
      yield assertTrue(
        encoded == pinnedCore,
        pinned == pinnedCore || pinned == pinnedCore + "\n"
      )
    },

    test("the golden document decodes back to the archive it pins") {
      for
        path <- ZIO.fromEither(repoFile("contracts", "sessions", "expected", "archive-golden.json"))
        archive <- ZIO.fromEither(goldenArchive)
        decoded = ConversationArchive.decodeDocument(readPinned(path))
      yield assertTrue(decoded == Right(archive))
    },

    test("absent label is omitted, never null; round-trip preserves it") {
      val archive = mkArchive("conv-x").toOption.get
      val encoded = ConversationArchive.encodeDocument(archive)
      val decoded = ConversationArchive.decodeDocument(encoded)
      assertTrue(
        !encoded.contains("\"label\""),
        decoded == Right(archive.copy(archivedAtMs = 0L))
      )
    },

    test("rejected corpus: every broken document is a typed refusal") {
      for
        path <- ZIO.fromEither(repoFile("contracts", "sessions", "expected", "archive-rejected.json"))
        corpus <- ZIO
          .fromEither(readPinned(path).fromJson[List[RejectedCase]])
          .mapError(e => new AssertionError(s"rejected corpus does not decode: $e"))
        checks = corpus.map { c =>
          ConversationArchive.decodeDocument(c.document.toJson) match
            case Left(reason) => c.name -> (reason.startsWith(c.reasonPrefix), reason)
            case Right(_)    => c.name -> (false, "accepted a broken document")
        }
      yield assertTrue(
        corpus.nonEmpty,
        checks.map(_._2._1).forall(identity),
        checks.collect { case (name, (false, got)) => s"$name: $got" }.isEmpty
      )
    },

    test("store: save/load round-trip lands at <sessionId>.json") {
      withTempDir { base =>
        for
          archive <- ZIO.fromEither(mkArchive("conv-roundtrip"))
          path <- ConversationStore(base).save(archive)
          loaded <- ConversationStore(base).load("conv-roundtrip")
        yield assertTrue(
          path.getFileName.toString == "conv-roundtrip.json",
          loaded == archive
        )
      }
    },

    test("store: missing vs corrupt documents are distinct typed refusals") {
      withTempDir { base =>
        for
          missing <- ConversationStore(base).load("conv-missing").either
          _ <- ZIO.attempt(Files.writeString(base.resolve("broken.json"), "{not json"))
          corrupt <- ConversationStore(base).load("broken").either
        yield assertTrue(
          missing.swap.exists(_.isInstanceOf[RaiderError.InputValidation]),
          missing.swap.exists(_.detail.contains("no archived conversation")),
          missing.swap.exists(_.isInstanceOf[RaiderError.InputValidation]),
          corrupt.swap.exists(_.isInstanceOf[RaiderError.InputValidation]),
          corrupt.swap.exists(_.detail.contains("corrupt"))
        )
      }
    },

    test("store: listing skips unreadable files and sorts ids") {
      withTempDir { base =>
        for
          a <- ZIO.fromEither(mkArchive("conv-b")).flatMap(ConversationStore(base).save(_))
          b <- ZIO.fromEither(mkArchive("conv-a")).flatMap(ConversationStore(base).save(_))
          _ <- ZIO.attempt(Files.writeString(base.resolve("zz-corrupt.json"), "nope"))
          ids <- ConversationStore(base).list()
        yield assertTrue(
          ids.map(_.value) == List("conv-a", "conv-b"),
          !ids.map(_.value).contains("zz-corrupt")
        )
      }
    },

    test("store: traversal and invalid ids never reach the filesystem") {
      withTempDir { base =>
        val store = ConversationStore(base)
        for
          refusedSave <- store.save(mkArchive("../escape").toOption.get).either
          refusedLoad <- store.load("a/b").either
        yield assertTrue(
          refusedSave.swap.exists(_.isInstanceOf[RaiderError.InputValidation]),
          refusedSave.swap.exists(_.detail.contains("invalid session id")),
          refusedLoad.swap.exists(_.isInstanceOf[RaiderError.InputValidation]),
          ConversationStore.sanitize("").isEmpty,
          ConversationStore.sanitize(".hidden").isEmpty,
          ConversationStore.sanitize("a" * 65).isEmpty,
          ConversationStore.sanitize("conv-demo-golden").isDefined
        )
      }
    }
  )
