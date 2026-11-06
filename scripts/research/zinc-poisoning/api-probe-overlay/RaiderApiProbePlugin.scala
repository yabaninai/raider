import sbt._
import Keys._

/** RAI-249 instrumentation (disposable experiment copies ONLY — never enable
  * in the product build). Auto-enables on every JVM project so that a single
  * overlay copy into project/ instruments the whole disposable build with no
  * build.sbt text patching.
  *
  * `apiProbeDump` is a read-only task: it depends on no compile/update task
  * and only records what zinc has already persisted, plus emitted-artifact
  * hashes. Running it never mutates the zinc state under observation.
  */
object RaiderApiProbePlugin extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = sbt.plugins.JvmPlugin

  object autoImport {
    val apiProbeDump = taskKey[Unit]("RAI-249: member-level dump of persisted zinc APIs + emitted hashes (read-only)")
  }

  import autoImport._

  override lazy val projectSettings = Seq(
    apiProbeDump := {
      ApiProbe.dump(baseDirectory.value, streams.value.log)
    }
  )
}
