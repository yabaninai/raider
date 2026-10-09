package raider.ui

/** Read models + providers of the `:plugins`/`:state` screens (card RAI-088).
  *
  * The UI owns NO IO: the data arrives through PURE snapshot providers wired
  * into [[TabShell]] at construction. Whoever constructs the shell owns the IO
  * (the plugin loader, RAI-075; the StateStore, RAI-076). The providers are
  * TOTAL: they never throw; an absent state store is `None`, not an error.
  */

/** One `:plugins` row: the loaded-plugin read model (name/version/active only —
  * no loader types leak into the UI).
  */
final case class PluginView(name: String, version: String, active: Boolean)

/** One `:state` scope read model: usage against the scope quotas (keys/bytes),
  * as served by the StateStore provider.
  */
final case class StateView(
    keys: Long,
    totalBytes: Long,
    maxKeys: Long,
    maxBytes: Long
)

/** Providers wired into [[TabShell]] (card RAI-088). Pure functions of the
  * current snapshot; called once per `:plugins`/`:state` command — never at
  * construction, never speculatively.
  */
final case class ShellViews(
    plugins: () => List[PluginView],
    state: String => Option[StateView]
)

object ShellViews:

  /** The detached default: no plugins, no state store — both screens render
    * their honest "(no plugins)" / typed-hint output.
    */
  val detached: ShellViews = ShellViews(() => Nil, _ => None)

/** Pure renderers of the `:plugins`/`:state` screens (card RAI-088). No
  * terminal, no effects — [[ShellFeedback.render]] feeds the exact lines to the
  * ONE writer ([[ShellTerminal.printAbove]]).
  */
object ShellScreens:

  /** `:plugins` (PSH-01): an aligned name/version/active table, or the honest
    * "(no plugins)" line when nothing is loaded.
    */
  def plugins(loaded: List[PluginView]): Vector[String] =
    if loaded.isEmpty then Vector("(no plugins)")
    else
      val nameW = (loaded.map(_.name.length) :+ "plugin".length).max
      val verW = (loaded.map(_.version.length) :+ "version".length).max
      val header =
        s"${"plugin".padTo(nameW, ' ')}  ${"version".padTo(verW, ' ')}  active"
      val rows = loaded.map { p =>
        val active = if p.active then "yes" else "no"
        s"${p.name.padTo(nameW, ' ')}  ${p.version.padTo(verW, ' ')}  $active"
      }
      Vector(s"plugins (${loaded.size}):", header) ++ rows

  /** `:state <scope>` (PSH-02): usage/quota lines for one scope. */
  def state(scope: String, usage: StateView): Vector[String] = Vector(
    s"state [$scope]:",
    s"  usage: ${usage.keys} keys, ${usage.totalBytes} bytes",
    s"  quota: ${usage.maxKeys} keys, ${usage.maxBytes} bytes"
  )

  /** `:state <scope>` with no store attached (PSH-02): a typed hint — never an
    * exception, never a silent ignore.
    */
  def stateMissing(scope: String): Vector[String] = Vector(
    s"hint: state store not attached (scope: $scope)"
  )
