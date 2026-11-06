package raider.compat.pi

/** pi -> Raider tool mapping table (card RAI-032; PIIMP-02 honesty rules).
  *
  * The mapping is intentionally narrow: only pi built-ins with a real Raider
  * equivalent are mapped; everything else is unsupported/rejected with an
  * explicit reason instead of being approximated. `bash` maps to `proc_run`
  * DEGRADED (argv-only, no shell, empty environment + allowlist).
  */
object PiToolMap:

  final case class Target(raider: String, degraded: Boolean, note: String)

  /** pi's default built-in child tools when frontmatter omits `tools` (per the
    * upstream README and sdk docs; extension tools remain a declared parity
    * gap, see PiProfileImporter.ParityGaps).
    */
  val piDefaultTools: List[String] = List("read", "bash", "edit", "write")

  private val mapping: Map[String, Target] = Map(
    "read" -> Target(
      "fs_read",
      degraded = false,
      note = "bounded ranged workspace read with sha256 provenance"
    ),
    "grep" -> Target(
      "fs_search",
      degraded = false,
      note = "bounded workspace content search (no external grep process)"
    ),
    "find" -> Target(
      "fs_tree",
      degraded = false,
      note = "bounded workspace tree listing with a file-name glob filter " +
        "(pi find locates files by pattern)"
    ),
    "ls" -> Target(
      "fs_tree",
      degraded = false,
      note = "same Raider listing capability as pi ls"
    ),
    "bash" -> Target(
      "proc_run",
      degraded = true,
      note = "argv-only, no shell; empty environment + explicit allowlist; " +
        "pi shell strings are not replayable verbatim"
    ),
    "edit" -> Target(
      "fs_edit",
      degraded = false,
      note = "structured single-file edit gated on expected sha256 provenance"
    )
  )

  private val explicitlyUnsupported: Map[String, String] = Map(
    "write" -> (
      "no Raider single-call file-create tool; the nearest fs_edit/" +
        "fs_patch_multi require an existing file's sha256 provenance - " +
        "rejected instead of approximated"
    ),
    "powershell" -> (
      "Windows shell variant; Raider proc_run is argv-only and not a shell"
    )
  )

  sealed trait Classified
  final case class Mapped(pi: String, target: Target) extends Classified
  final case class Unsupported(pi: String, reason: String) extends Classified

  def classify(name: String): Classified =
    mapping.get(name) match
      case Some(target) => Mapped(name, target)
      case None =>
        explicitlyUnsupported.get(name) match
          case Some(reason) => Unsupported(name, reason)
          case None =>
            if name.startsWith("mcp__") then
              Unsupported(
                name,
                "MCP tools are outside the import surface " +
                  "(no Raider MCP client in scope)"
              )
            else
              Unsupported(
                name,
                "not a pi built-in with a Raider equivalent and not in the " +
                  "mapping table"
              )
