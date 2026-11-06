package raider.compat.opencode

import zio.Chunk
import zio.ZIO
import zio.json.ast.Json

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

/** Loader for the vendored OpenCode profile corpus: verifies every manifest
  * entry by SHA-256 (so any tampered file fails the load), extracts the
  * registry agent defaults, and resolves the default agent like the pinned
  * agent.ts selectedDefault semantics.
  */
final case class Loaded(
  corpus: CorpusPin,
  defaultAgentId: String,
  profiles: Vector[Profile],
  baseline: Vector[PermissionRule],
  pathActions: Vector[String],
)

object Registry:

  def load(corpusRoot: Path): ZIO[Any, String, Loaded] =
    ZIO.fromEither(loadEither(corpusRoot))

  /** Configured id wins when the profile exists and is selectable (not hidden,
    * not a subagent — matching the upstream isPrimary semantics where only
    * primary/"all" agents can be the session default); otherwise build when
    * selectable; otherwise the first selectable profile; otherwise "build".
    */
  def resolve(profiles: Vector[Profile], configured: Option[String]): String =
    val selectable = profiles.filter(p => !p.hidden && p.mode != "subagent")
    val configuredId = configured.flatMap(id => selectable.find(_.id == id)).map(_.id)
    val fallback =
      selectable.find(_.id == "build").orElse(selectable.headOption).map(_.id).getOrElse("build")
    configuredId.getOrElse(fallback)

  /** Default agent first, then the remaining profiles in original order. */
  def ordered(profiles: Vector[Profile], configured: Option[String]): Vector[Profile] =
    val resolved = resolve(profiles, configured)
    (profiles.find(_.id == resolved) ++ profiles.filter(_.id != resolved)).toVector

  private def loadEither(root: Path): Either[String, Loaded] =
    try
      val manifestBytes = Files.readAllBytes(root.resolve("MANIFEST.json"))
      val manifestSha = sha256(manifestBytes)
      val manifest = parseJson(new String(manifestBytes, StandardCharsets.UTF_8), "MANIFEST.json")
      for
        upstream <- field(manifest, "upstream").collect { case o @ Json.Obj(_) => o }
          .toRight("MANIFEST.json is missing the upstream section")
        repo <- strField(upstream, "repo").toRight("MANIFEST.json is missing upstream.repo")
        commit <- strField(upstream, "commit").toRight("MANIFEST.json is missing upstream.commit")
        license <- strField(upstream, "license").toRight("MANIFEST.json is missing upstream.license")
        files <- field(manifest, "files").collect { case Json.Arr(items) => items }
          .toRight("MANIFEST.json is missing the files list")
        _ <- verifyFiles(root, files)
        defaults <- loadJson(root.resolve("extracted/defaults.json"))
        defaultsObj <- asObj(defaults, "defaults.json")
        registry <- loadJson(root.resolve("extracted/registry-agents.json"))
        registryObj <- asObj(registry, "registry-agents.json")
        defaultAgentId <- strField(defaultsObj, "defaultAgentId")
          .toRight("defaults.json is missing defaultAgentId")
        pathActions <- strArray(defaultsObj, "pathActions")
          .toRight("defaults.json is missing pathActions")
        baseline <- rulesField(registryObj, "baselinePermissions")
          .toRight("registry-agents.json is missing baselinePermissions")
        agents <- field(registryObj, "agents").collect { case Json.Arr(items) => items }
          .toRight("registry-agents.json is missing the agents list")
        profiles <- agents
          .map(agent => profileFromRegistry(agent))
          .foldRight[Either[String, Vector[Profile]]](Right(Vector.empty))((next, acc) =>
            for
              rest <- acc
              p <- next
            yield p +: rest
          )
      yield Loaded(CorpusPin(repo, commit, license, manifestSha), defaultAgentId, profiles, baseline, pathActions)
      catch case e: Exception => Left(s"failed to load corpus: $e")

  private def verifyFiles(root: Path, files: Chunk[Json]): Either[String, Unit] =
    files.foreach { entry =>
      entry match
        case obj @ Json.Obj(_) =>
          for
            path <- obj.get("path").collect { case Json.Str(s) => s }
            expected <- obj.get("sha256").collect { case Json.Str(s) => s }
          do
            val file = root.resolve(path)
            if !Files.isRegularFile(file) then return Left(s"corpus file missing: $path")
            val actual = sha256(Files.readAllBytes(file))
            if actual != expected then return Left(s"corpus file hash mismatch: $path")
        case _ => return Left("MANIFEST.json files list contains a non-object entry")
      ()
    }
    Right(())

  private def profileFromRegistry(agent: Json): Either[String, Profile] =
    agent match
      case obj @ Json.Obj(_) =>
        for
          id <- strField(obj, "id").toRight("registry agent is missing id")
          name <- strField(obj, "name").toRight(s"registry agent $id is missing name")
          mode <- strField(obj, "mode").getOrElse("subagent") match
            case m if Model.modes.contains(m) => Right(m)
            case other => Left(s"registry agent $id has invalid mode $other")
          permissions <- rulesField(obj, "permissions")
            .toRight(s"registry agent $id is missing permissions")
        yield
          Profile(
            id = id,
            name = name,
            description = strField(obj, "description"),
            mode = mode,
            hidden = boolField(obj, "hidden").getOrElse(false),
            color = strField(obj, "color").filter(_.matches("""#[0-9a-fA-F]{6}""")),
            steps = intField(obj, "steps").filter(_ > 0),
            model = strField(obj, "model").flatMap(NativeDecode.parseNativeModel),
            request = None,
            system = strField(obj, "system"),
            permissions = permissions,
            source = "registry",
            origin = "opencode",
            sourcePath = "extracted/registry-agents.json",
            unsupportedFields = Vector.empty,
          )
      case _ => Left("registry-agents.json agents list contains a non-object entry")

  private def rulesField(obj: Json.Obj, key: String): Option[Vector[PermissionRule]] =
    obj.get(key).collect { case Json.Arr(items) => items }.map { items =>
      items.flatMap {
        case rule @ Json.Obj(_) =>
          for
            action <- strField(rule, "action")
            resource <- strField(rule, "resource")
            effect <- strField(rule, "effect") if Model.effects.contains(effect)
          yield PermissionRule(action, resource, effect)
        case _ => None
      }.toVector
    }

  private def parseJson(text: String, what: String): Json =
    Json.decoder.decodeJson(text) match
      case Right(v)  => v
      case Left(err) => throw new IllegalArgumentException(s"$what is not valid JSON: $err")

  private def asObj(v: Json, what: String): Either[String, Json.Obj] = v match
    case obj @ Json.Obj(_) => Right(obj)
    case _                 => Left(s"$what is not a JSON object")

  private def loadJson(file: Path): Either[String, Json] =
    try
      val v = parseJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), file.getFileName.toString)
      Right(v)
    catch case e: Exception => Left(s"${file.getFileName} is not valid JSON: ${e.getMessage}")

  private def field(v: Json, key: String): Option[Json] = v match
    case obj @ Json.Obj(_) => obj.get(key)
    case _                 => None

  private def strField(obj: Json.Obj, key: String): Option[String] =
    obj.get(key).collect { case Json.Str(s) => s }

  private def boolField(obj: Json.Obj, key: String): Option[Boolean] =
    obj.get(key).collect { case Json.Bool(b) => b }

  private def intField(obj: Json.Obj, key: String): Option[Int] =
    obj.get(key).flatMap {
      case Json.Num(n) =>
        val bd = BigDecimal(n)
        if bd.isValidInt then Some(bd.toInt) else None
      case _ => None
    }

  private def strArray(v: Json, key: String): Option[Vector[String]] =
    field(v, key).collect { case Json.Arr(items) =>
      items.flatMap {
        case Json.Str(s) => Vector(s)
        case _           => Vector.empty
      }.toVector
    }

  private def sha256(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(bytes).map(b => f"${b & 0xff}%02x").mkString
