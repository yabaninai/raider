package raider.cli.portable

import zio.test.*
import zio.test.Assertion.*

/** The launch-layer intersection (RAI-199): the portable launch admits the
  * LOWER of the document's declared limits and the CLI invocation's own
  * ceilings. The document is untrusted input, so its declared ceilings must get
  * launcher-side teeth: attempts clamp to the minimum, concurrency clamps into
  * the admission domain, the token ceiling feeds the wire envelope and the
  * OUTER deadline is the minimum of `--deadline-s` and the declared
  * `deadlineMs`. These are the exact arithmetic cases the run route depends on.
  */
object PortableLaunchPolicySpec extends ZIOSpecDefault:

  import PortableLaunchPolicy.*
  import raider.dsl.portable.PortableLaunchMetadata

  private def declared(
      attempts: Int = 8,
      parallelism: Int = 8,
      deadlineMs: Long = 60000L,
      tokens: Long = 12000L,
      children: Int = 0
  ): PortableLaunchMetadata =
    PortableLaunchMetadata(
      maxAttempts = attempts,
      maxParallelism = parallelism,
      deadlineMs = deadlineMs,
      maxTokens = tokens,
      maxChildren = children
    )

  private def invocation(
      attempts: Int = 8,
      deadlineSeconds: Option[Long] = None
  ): InvocationLimits =
    InvocationLimits(attempts, deadlineSeconds)

  private def launch(
      d: PortableLaunchMetadata,
      i: InvocationLimits
  ): EffectiveLaunch =
    intersect(d, i) match
      case Right(e) => e
      case Left(e) =>
        throw new IllegalStateException(
          "intersection refused a sane fixture: " + e.detail
        )

  def spec =
    suite("raider portable launch intersection (document x invocation)")(
      test(
        "the document's tighter attempt budget wins; the invocation's tighter one clamps"
      ) {
        val a = launch(declared(attempts = 2), invocation(attempts = 8))
        val b = launch(declared(attempts = 10), invocation(attempts = 3))
        assert(a.limits.maxAttempts)(equalTo(2)) &&
        assert(b.limits.maxAttempts)(equalTo(3))
      },
      test("rounds per agent follow the intersected attempt budget") {
        val e = launch(declared(attempts = 3), invocation(attempts = 8))
        assert(e.maxRoundsPerAgent)(equalTo(3))
      },
      test("the single-dispatch discipline: maxLLM is always 1") {
        val e = launch(declared(parallelism = 64), invocation())
        assert(e.limits.maxConcurrentLlm)(equalTo(1))
      },
      test(
        "tool concurrency: declared 0 or negative clamps to 1; declared over 64 clamps; a sane value passes"
      ) {
        val zero = launch(declared(parallelism = 0), invocation())
        val huge = launch(declared(parallelism = 100), invocation())
        val sane = launch(declared(parallelism = 8), invocation())
        assert(zero.limits.maxConcurrentTools)(equalTo(1)) &&
        assert(huge.limits.maxConcurrentTools)(equalTo(64)) &&
        assert(sane.limits.maxConcurrentTools)(equalTo(8))
      },
      test(
        "child concurrency: the declared 0 case still runs the root-only workflow (>= 1)"
      ) {
        val e = launch(declared(children = 0), invocation())
        assert(e.limits.maxChildren)(equalTo(1))
      },
      test("the token ceiling is the declared token budget") {
        val e = launch(declared(tokens = 12000L), invocation())
        assert(e.tokenCeiling)(equalTo(12000L))
      },
      test(
        "the outer deadline is the minimum of the declared deadlineMs and the CLI --deadline-seconds"
      ) {
        val docOnly = launch(
          declared(deadlineMs = 5000L),
          invocation(deadlineSeconds = None)
        )
        val cliWins = launch(
          declared(deadlineMs = 60000L),
          invocation(deadlineSeconds = Some(10L))
        )
        val docWins = launch(
          declared(deadlineMs = 5000L),
          invocation(deadlineSeconds = Some(90L))
        )
        assert(docOnly.outerDeadline.map(_.toMillis))(isSome(equalTo(5000L))) &&
        assert(cliWins.outerDeadline.map(_.toMillis))(
          isSome(equalTo(10000L))
        ) &&
        assert(docWins.outerDeadline.map(_.toMillis))(isSome(equalTo(5000L)))
      },
      test(
        "a non-positive declared deadline is treated as no declared bound (defensive)"
      ) {
        val e =
          launch(declared(deadlineMs = 0L), invocation(deadlineSeconds = None))
        val f = launch(
          declared(deadlineMs = 0L),
          invocation(deadlineSeconds = Some(7L))
        )
        assert(e.outerDeadline)(isNone) &&
        assert(f.outerDeadline.map(_.toMillis))(isSome(equalTo(7000L)))
      }
    )

end PortableLaunchPolicySpec
