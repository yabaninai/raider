package raider.core

/** Job: an opaque handle to a running (or finished) root/child execution
  * (runtime-contracts §3–4). Control verbs (`await/send/cancel/watch/info`)
  * live in the launch facade, not here. `await` is idempotent and multiple
  * waiters observe one terminal outcome; heterogeneous access happens only at
  * the admin boundary with codecs.
  */
trait Job[A]:
  def id: JobId
  def root: RootId

/** Child: a fork handle joined within the owning workflow scope. */
trait Child[A]:
  def job: Job[A]
  def join: Task[A]
