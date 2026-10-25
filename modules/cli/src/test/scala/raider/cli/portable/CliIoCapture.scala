package raider.cli.portable

/** Shared capture gate for specs that redirect the global System.out around
  * in-process command invocations. zio-test runs suites in parallel; without
  * this lock, concurrent redirection from PortableCheckSpec and
  * PortableExplainSpec would cross-contaminate the captured machine output.
  */
object CliIoCapture:
  val lock: Object = new Object
