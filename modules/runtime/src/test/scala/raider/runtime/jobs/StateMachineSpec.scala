package raider.runtime.jobs

import zio.test.*

/** Property/matrix test of the §4 lifecycle edges: every (from, to) pair is
  * checked against the specification table; terminal statuses absorb.
  */
object StateMachineSpec extends ZIOSpecDefault:

  private val all: List[JobStatus] =
    List(
      JobStatus.Queued,
      JobStatus.Running,
      JobStatus.Waiting,
      JobStatus.WaitingApproval,
      JobStatus.Cancelling,
      JobStatus.Succeeded,
      JobStatus.Failed,
      JobStatus.Cancelled,
      JobStatus.Interrupted,
      JobStatus.Uncertain
    )

  private val legal: Set[(JobStatus, JobStatus)] = Set(
    (JobStatus.Queued, JobStatus.Running),
    (JobStatus.Queued, JobStatus.Cancelling),
    (JobStatus.Running, JobStatus.Waiting),
    (JobStatus.Running, JobStatus.WaitingApproval),
    (JobStatus.Running, JobStatus.Cancelling),
    (JobStatus.Running, JobStatus.Succeeded),
    (JobStatus.Running, JobStatus.Failed),
    (JobStatus.Waiting, JobStatus.Running),
    (JobStatus.Waiting, JobStatus.Cancelling),
    (JobStatus.Waiting, JobStatus.Succeeded),
    (JobStatus.Waiting, JobStatus.Failed),
    (JobStatus.WaitingApproval, JobStatus.Running),
    (JobStatus.WaitingApproval, JobStatus.Cancelling),
    (JobStatus.Cancelling, JobStatus.Cancelled),
    (JobStatus.Cancelling, JobStatus.Interrupted),
    (JobStatus.Cancelling, JobStatus.Uncertain)
  )

  def spec = suite("JobStatus §4 edge matrix")(
    test("every pair matches the specification table") {
      val mismatches =
        for
          from <- all
          to <- all
        yield
          val expected = legal.contains((from, to))
          val actual = JobStatus.canTransition(from, to)
          if actual != expected then
            List(s"$from->$to expected=$expected actual=$actual")
          else Nil
      val flat = mismatches.flatten
      assertTrue(
        flat.isEmpty && flat.take(5).mkString("; ").length >= 0 && flat.isEmpty
      )
    },
    test("terminal statuses are absorbing (no outgoing edges)") {
      val leaks = for
        from <- all.filter(JobStatus.terminal.contains)
        to <- all
        if JobStatus.canTransition(from, to)
      yield s"$from->$to"
      assertTrue(leaks.isEmpty)
    },
    test(
      "every non-terminal status can reach some terminal status (transitively)"
    ) {
      // BFS over the legal edges: a healthy lifecycle has no livelock states
      def reachable(from: JobStatus): Set[JobStatus] =
        var frontier = Set(from)
        var seen = Set(from)
        while frontier.nonEmpty do
          val next = frontier
            .flatMap(f => all.filter(to => JobStatus.canTransition(f, to)))
            .filterNot(seen)
          seen ++= next
          frontier = next
        seen
      val stuck = all
        .filterNot(JobStatus.terminal.contains)
        .filterNot(f => reachable(f).exists(JobStatus.terminal.contains))
      assertTrue(stuck.isEmpty)
    }
  )
