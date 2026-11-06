package raider.compat.opencode

import zio.ZIO
import zio.json.ast.Json

import java.nio.file.Path
import scala.collection.mutable

/** Builds the OpenCode profile import document: loads the verified corpus,
  * applies JSON config overlays and markdown agent documents in document
  * order, pushes global permission rules onto every profile, expands home
  * references for path actions, and resolves the default agent.
  */
object Importer:

  sealed trait Document

  object Document:
    final case class Markdown(path: String, content: String) extends Document
    final case class Json(path: String, json: zio.json.ast.Json) extends Document

  final case class Input(documents: Vector[Document], home: Option[String] = None)

  private val PathActionsFallback: Vector[String] = Vector("external_directory", "read", "edit")

  def importDocument(corpusRoot: Path, input: Input): ZIO[Any, String, ImportDocument] =
    Registry.load(corpusRoot).flatMap { loaded =>
      ZIO.fromEither(transform(loaded, input))
    }

  def transform(loaded: Loaded, input: Input): Either[String, ImportDocument] =
    // 1. Decode every document up front; a malformed JSON document fails the import.
    val decoded = mutable.ListBuffer.empty[(Document, Either[String, Overlay], Vector[MarkdownProfiles.Decoded])]
    input.documents.foreach {
      case doc @ Document.Json(path, json) =>
        JsonConfig.decode(json, path) match
          case Right(overlay) => decoded += ((doc, Right(overlay), Vector.empty))
          case Left(err)      => decoded += ((doc, Left(err), Vector.empty))
      case doc @ Document.Markdown(path, content) =>
        val f = MarkdownProfiles.MarkdownFile(path, content)
        val d =
          if path.startsWith("mode/") || path.startsWith("modes/") then MarkdownProfiles.decodeMode(f)
          else MarkdownProfiles.decodeAgent(f)
        decoded += ((doc, Right(Overlay(Vector.empty, Vector.empty, None, None, Vector.empty)), Vector(d)))
    }
    decoded.collectFirst { case (_, Left(err), _) => err } match
      case Some(err) => Left(err)
      case None =>
        val overlays = decoded.collect { case (d: Document.Json, Right(o), _) => (d.path, o) }.toVector
        val markdownEntries = decoded.collect { case (d: Document.Markdown, _, dec) => (d.path, dec.head) }.toVector

        // 2. Global rules from all JSON documents, applied to every existing profile.
        val globalRules = overlays.flatMap(_._2.globalRules)
        val withGlobals = loaded.profiles.map(p => p.copy(permissions = p.permissions ++ globalRules))

        // 3. Apply entries in document order: JSON overlays (small_model first) and markdown.
        val diags = mutable.ListBuffer.empty[Diagnostic]
        overlays.foreach((docPath, overlay) => diags ++= overlay.diagnostics)
        markdownEntries.foreach((_, d) => diags ++= d.diagnostics)

        val profiles = mutable.ListBuffer.empty[Profile]
        profiles ++= withGlobals

        input.documents.foreach {
          case Document.Json(docPath, _) =>
            val overlay = overlays.find(_._1 == docPath).map(_._2).getOrElse(Overlay(Vector.empty, Vector.empty, None, None, Vector.empty))
            overlay.smallModel.foreach(patch => applyPatch(profiles, patch, docPath, "json-config", loaded.baseline, globalRules, diags))
            overlay.entries.foreach(patch => applyPatch(profiles, patch, docPath, "json-config", loaded.baseline, globalRules, diags))
          case Document.Markdown(docPath, _) =>
            markdownEntries.find(_._1 == docPath).map(_._2).foreach { d =>
              d.removedId.foreach { id =>
                diags += Diagnostic("unsupported", Vector(docPath, id), "removed by disabled flag")
                removeProfile(profiles, id)
              }
              d.profile.foreach { p =>
                applyPatch(profiles, patchFromMarkdown(p), docPath, "markdown", loaded.baseline, globalRules, diags)
              }
            }
        }

        // 4. Default agent: the last JSON document wins, resolved like selectedDefault.
        val configured = overlays.lastOption.flatMap(_._2.defaultAgent)
        val ordered = Registry.ordered(profiles.toVector, configured)

        // 5. Home expansion for path actions only.
        val expanded = input.home match
          case Some(home) => ordered.map(p => p.copy(permissions = p.permissions.map(expandRule(_, home, loaded.pathActions))))
          case None       => ordered

        Right(
          ImportDocument(
            schemaVersion = 1,
            corpus = loaded.corpus,
            defaultAgentId = Registry.resolve(ordered, configured),
            profiles = expanded,
            diagnostics = diags.toVector,
          )
        )

  private def applyPatch(
    profiles: mutable.ListBuffer[Profile],
    patch: AgentPatch,
    docPath: String,
    source: String,
    baseline: Vector[PermissionRule],
    globalRules: Vector[PermissionRule],
    diags: mutable.ListBuffer[Diagnostic],
  ): Unit =
    if patch.disabled then
      diags += Diagnostic("unsupported", Vector(docPath, patch.id), "removed by disabled flag")
      removeProfile(profiles, patch.id)
    else
      val index = profiles.indexWhere(_.id == patch.id)
      if index >= 0 then profiles.update(index, mergeProfile(profiles(index), patch))
      else
        profiles += Profile(
          id = patch.id,
          name = patch.name.orElse(patch.description).getOrElse(patch.id),
          description = patch.description,
          mode = patch.mode.getOrElse("subagent"),
          hidden = patch.hidden.getOrElse(false),
          color = patch.color,
          steps = patch.steps,
          model = patch.model,
          request = patch.request,
          system = patch.system,
          permissions = baseline ++ globalRules ++ patch.permissions,
          source = source,
          origin = patch.origin,
          sourcePath = docPath,
          unsupportedFields = patch.unsupportedFields,
        )

  private def removeProfile(profiles: mutable.ListBuffer[Profile], id: String): Unit =
    profiles.indexWhere(_.id == id) match
      case -1   => ()
      case idx  => profiles.remove(idx)

  private def patchFromMarkdown(p: Profile): AgentPatch =
    AgentPatch(
      id = p.id,
      name = Some(p.name),
      description = p.description,
      system = p.system,
      mode = Some(p.mode),
      hidden = Some(p.hidden),
      color = p.color,
      steps = p.steps,
      model = p.model,
      request = p.request,
      permissions = p.permissions,
      disabled = false,
      unsupportedFields = p.unsupportedFields,
      origin = p.origin,
    )

  private def mergeProfile(existing: Profile, patch: AgentPatch): Profile =
    existing.copy(
      name = patch.name.getOrElse(existing.name),
      description = patch.description.orElse(existing.description),
      mode = patch.mode.getOrElse(existing.mode),
      hidden = patch.hidden.getOrElse(existing.hidden),
      color = patch.color.orElse(existing.color),
      steps = patch.steps.orElse(existing.steps),
      model = patch.model.orElse(existing.model),
      request = mergeRequest(existing.request, patch.request),
      system = patch.system.orElse(existing.system),
      permissions = existing.permissions ++ patch.permissions,
      unsupportedFields = (existing.unsupportedFields ++ patch.unsupportedFields).distinct,
      origin = if patch.origin == "native-v2" then "native-v2" else existing.origin,
    )

  /** Pinned merge semantics: headers and body maps are merged, new keys win. */
  private[opencode] def mergeRequest(
    existing: Option[RequestSettings],
    patch: Option[RequestSettings],
  ): Option[RequestSettings] =
    (existing, patch) match
      case (None, p)             => p
      case (e, None)             => e
      case (Some(e), Some(p)) =>
        Some(
          RequestSettings(
            headers = mergeMaps(e.headers, p.headers),
            body = (e.body, p.body) match
              case (None, b) => b
              case (b, None) => b
              case (Some(eb), Some(pb)) =>
                val merged = mutable.LinkedHashMap.empty[String, Json]
                eb.fields.foreach { case (k, v) => merged.update(k, v) }
                pb.fields.foreach { case (k, v) => merged.update(k, v) }
                Some(Json.Obj(merged.toList*))
          )
        )

  private def mergeMaps(
    existing: Option[Map[String, String]],
    patch: Option[Map[String, String]],
  ): Option[Map[String, String]] =
    (existing, patch) match
      case (None, p) => p
      case (e, None) => e
      case (Some(e), Some(p)) =>
        val merged = mutable.LinkedHashMap.empty[String, String]
        e.foreach { case (k, v) => merged.update(k, v) }
        p.foreach { case (k, v) => merged.update(k, v) }
        Some(merged.toMap)

  private def expandRule(r: PermissionRule, home: String, pathActions: Vector[String]): PermissionRule =
    if pathActions.contains(r.action) || (pathActions.isEmpty && PathActionsFallback.contains(r.action)) then
      r.copy(resource = expandHome(r.resource, home))
    else r

  /** Pinned expandHome semantics: only bare ~, ~/, and $HOME[ /] forms. */
  private[opencode] def expandHome(resource: String, home: String): String =
    val posix = resource.replace('\\', '/')
    if posix == "~" || posix == "~/" then home
    else if posix.startsWith("~/") then home + posix.substring(1)
    else if posix == "$HOME" || posix == "$HOME/" then home
    else if posix.startsWith("$HOME/") then home + posix.substring(5)
    else resource
