package raider.fixtures

import raider.core.{Program, Task}
import raider.core.BundleWorkflow

/** Compiled fixture bundle workflow (RAI-022 tests): jarred at test time and
  * loaded through BundleLoader. Pure program — no backend, no network. */
class DemoWorkflow extends BundleWorkflow:
  def name: String = "inspect"
  def program: Program[String, String] =
    Program.fromFunction("inspect")(input => Task.succeed(s"inspected: $input"))

class SecondWorkflow extends BundleWorkflow:
  def name: String = "second"
  def program: Program[String, String] =
    Program.fromFunction("second")(input => Task.succeed(s"second: $input"))
