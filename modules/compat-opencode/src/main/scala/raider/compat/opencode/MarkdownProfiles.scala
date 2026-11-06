package raider.compat.opencode

/** Decoding of OpenCode agent markdown files, faithful to the pinned
  * ConfigMarkdown/ConfigPluginAgent decode (vendor/opencode-profiles/upstream/
  * core/config-markdown.ts and config-plugin-agent.ts): frontmatter keys not
  * in the agent key set mark a legacy V1 file (migrated via migrateAgent)
  * unless native keys are present, in which case the file decodes natively
  * and unsupported keys are dropped with a diagnostic. A decoded profile
  * carries migrated rules only; the baseline permission set is pushed at
  * install time by the importer.
  */
object MarkdownProfiles:

  final case class MarkdownFile(path: String, content: String)

  final case class Decoded(profile: Option[Profile], diagnostics: Vector[Diagnostic], removedId: Option[String] = None)

  private val AgentDirs: Set[String] = Set("agent", "agents", "mode", "modes")

  private val AgentKeys: Set[String] = Set(
    "variant", "model", "request", "system", "description", "mode", "hidden",
    "color", "steps", "disabled", "permissions",
  )

  private val NativeKeys: Set[String] = Set("permissions", "request", "system", "disabled")

  private def invalid(path: Vector[String], message: String): Diagnostic =
    Diagnostic("invalid", path, message)

  /** agent/research/deep.md -> research/deep; tools/x.md -> None. */
  def idFromPath(path: String): Option[String] =
    val parts = path.split("/")
    if parts.length >= 2 && AgentDirs.contains(parts.head) && parts.last.endsWith(".md") then
      Some(parts.drop(1).mkString("/").stripSuffix(".md"))
    else None

  def decodeAgent(f: MarkdownFile): Decoded = decode(f, forcePrimary = false)

  /** Mode directory files are forced to mode primary. */
  def decodeMode(f: MarkdownFile): Decoded = decode(f, forcePrimary = true)

  private def decode(f: MarkdownFile, forcePrimary: Boolean): Decoded =
    val fm = Frontmatter.parse(f.content)
    val baseDiags = fm.issues.map(i => invalid(Vector(f.path), i))
    val idOpt = idFromPath(f.path)
    if idOpt.isEmpty then Decoded(None, baseDiags :+ invalid(Vector(f.path), "not an agent markdown path"))
    else if baseDiags.nonEmpty then Decoded(None, baseDiags)
    else
      val id = idOpt.get
      val keys = Frontmatter.keys(fm.fields).toSet
      val unsupported = keys.filterNot(AgentKeys.contains)
      val nativePresent = NativeKeys.intersect(keys).nonEmpty
      if unsupported.nonEmpty && !nativePresent then decodeLegacy(f, id, fm, forcePrimary)
      else decodeNative(f, id, fm, forcePrimary)

  private def decodeLegacy(f: MarkdownFile, id: String, fm: Frontmatter.Parsed, forcePrimary: Boolean): Decoded =
    LegacyMigrate.migrateAgent(fm.fields, forcePrimary) match
      case Left(diags) =>
        Decoded(None, diags.map(d => d.copy(path = f.path +: d.path)))
      case Right(m) =>
        if m.disabled then
          Decoded(
            None,
            Vector(Diagnostic("unsupported", Vector(f.path, id), "removed by disable flag")),
            removedId = Some(id),
          )
        else
          val body = fm.body.trim
          val profile = Profile(
            id = id,
            name = m.name.orElse(m.description).getOrElse(id),
            description = m.description,
            mode = m.mode.getOrElse("subagent"),
            hidden = m.hidden.getOrElse(false),
            color = m.color,
            steps = m.steps,
            model = m.model,
            request = m.request,
            system = if body.isEmpty then None else Some(body),
            permissions = m.rules,
            source = "markdown",
            origin = "legacy-v1",
            sourcePath = f.path,
            unsupportedFields = Vector.empty,
          )
          Decoded(Some(profile), Vector.empty)

  private def decodeNative(f: MarkdownFile, id: String, fm: Frontmatter.Parsed, forcePrimary: Boolean): Decoded =
    val body = fm.body.trim
    val fallback = if body.isEmpty then None else Some(body)
    NativeDecode.decode(fm.fields, fallback) match
      case Left(diags) =>
        Decoded(None, diags.map(d => d.copy(path = f.path +: d.path)))
      case Right(a) =>
        val diags = fm.fields.fields.collect {
          case (key, _) if a.unsupported.contains(key) =>
            Diagnostic("unsupported", Vector(f.path, key), "dropped unsupported key")
        }.toVector
        if a.disabled then
          Decoded(
            None,
            diags :+ Diagnostic("unsupported", Vector(f.path, id), "removed by disabled flag"),
            removedId = Some(id),
          )
        else
          val mode = if forcePrimary then Some("primary") else a.mode
          val profile = Profile(
            id = id,
            name = a.name.getOrElse(id),
            description = a.description,
            mode = mode.getOrElse("subagent"),
            hidden = a.hidden.getOrElse(false),
            color = a.color,
            steps = a.steps,
            model = a.model,
            request = a.request,
            system = a.system,
            permissions = a.permissions,
            source = "markdown",
            origin = "native-v2",
            sourcePath = f.path,
            unsupportedFields = a.unsupported.toVector,
          )
          Decoded(Some(profile), diags)
