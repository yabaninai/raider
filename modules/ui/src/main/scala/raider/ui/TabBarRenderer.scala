package raider.ui

/** Pure tab-bar rendering (card RAI-061): rows + one hint line, over the
  * registry read model only. No terminal, no effects.
  */
object TabBarRenderer:

  /** The bar: creation order; `*` marks the focused tab; each row carries the
    * label, the session status and the unread badge (suppressed at 0). The
    * status is the ONE canonical [[raider.runtime.events.StatusText]] line
    * carried by [[TabView]] (RAI-097 remainder: unified format with the CLI) —
    * this renderer has no local status format of its own.
    */
  def tabBar(tabs: List[TabView], focused: Option[TabId]): Vector[String] =
    if tabs.isEmpty then Vector("no open tabs (start one with :new <label>)")
    else
      val rows = tabs.zipWithIndex.map { (t, i) =>
        val mark = if focused.contains(t.id) then "*" else " "
        val unread =
          if t.unreadCount > 0 then s" (${t.unreadCount} unread)" else ""
        s"$mark${i + 1}. ${t.label} [${t.statusText}]$unread"
      }.toVector
      rows :+ hintBar

  /** `:help` body (SHELL-02 palette). */
  val helpLines: Vector[String] = Vector(
    "commands:",
    "  :tabs               show the tab bar",
    "  :sessions           list open chat sessions (use :switch to focus)",
    "  :switch <n|label>   focus a tab by 1-based number or label",
    "  :new <label>        new tab (synthetic job/root binding until RAI-062)",
    "  :next               focus the next tab (creation order, wrapping)",
    "  :prev               focus the previous tab",
    "  :rename <label>     rename the focused tab",
    "  :close              close the focused tab (running: hide or cancel choice)",
    "  :plugins            loaded plugins (name/version/active; RAI-088)",
    "  :state <scope>      state store usage/quota for one scope (RAI-088)",
    "  :aux                the one auxiliary tab (provider-backed; LIN-04A)",
    "  :help               this help",
    "  :quit               exit the shell"
  )

  /** The one-line hint bar under every tab-bar render (palette/hint bar). */
  val hintBar: String =
    "tabs: :tabs :sessions :switch <n|label> :new <label> :next :prev :rename <label> :close :plugins :state <scope> :aux :help :quit"
