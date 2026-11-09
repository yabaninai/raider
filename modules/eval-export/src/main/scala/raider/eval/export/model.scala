package raider.evalexport

/** Versioned input model of the agent-eval export (card RAI-040).
  *
  * The exporter is a PURE transform: an [[EvalRunInput]] assembled by the
  * caller from journal/trace/verifier evidence is turned into an
  * [[ExportBundle]] of six pinned JSON documents (Yabanin-compatible run
  * directory shape: manifest.json, episodes.jsonl, calls.jsonl, tools.jsonl,
  * verdicts.jsonl, plus this contract's append-only reconciliation.jsonl). The
  * module is standalone: no server, no database, no compile-time dependency on
  * other Raider modules.
  *
  * Honesty-critical shape constraints of the input (EXPORT-02):
  *   - tool arguments travel as raw text ([[ToolCallInput.argsJsonText]]) that
  *     is NEVER exported verbatim — only its sha256 digest is; embedding args
  *     into the export requires the explicit [[ToolCallInput.argsRedactedSafe]]
  *     marker (fixture-pinned redacted-safe args);
  *   - environment references are NAME-only ([[ToolCallInput.envRefs]]); no
  *     field of this model can carry an environment VALUE (typed
  *     [[ExportError.BadEnvRef]] rejects name=value pairs at the door);
  *   - verdicts exist ONLY as actual [[VerifierResult]] records supplied by the
  *     caller; the exporter cannot synthesize one for an episode whose verifier
  *     produced nothing — such episodes are exported as pending/absent with an
  *     explicit reason;
  *   - usage keeps unknown prices null ([[Usage]]); claiming a known price with
  *     no number is a typed [[ExportError.BadUsage]].
  *
  * Versioning: every exported document carries `schemaVersion` (see
  * [[model.schemaVersion]]). Any shape change is a new schemaVersion plus a
  * contract revision (contracts/eval-export), never a silent change.
  */
object model:

  /** Wire revision pinned by this module's codecs and schemas. */
  val schemaVersion: Int = 1

  /** Producing toolchain of the export (mirrors the lineage contract generator
    * shape). `name` must be exactly `raider`.
    */
  final case class Generator(
      name: String,
      version: String,
      scalaVersion: String,
      jdkVersion: String
  )

  /** Identity of the evaluated run: one export = one run = one lineage root. */
  final case class RunInfo(
      runId: String,
      generator: Generator,
      model: String,
      startedAtMs: Long,
      /** None while the run has no terminal evidence — exported as explicit
        * null and forces runStatus=partial; never invented.
        */
      finishedAtMs: Option[Long]
  )

  /** One job of the run's root→job→attempt lineage. `parentJobId` is null for
    * the single root job; parents must appear earlier in `jobs` (the same
    * parents-before-children discipline as the lineage data contract).
    */
  final case class JobRecord(
      jobId: String,
      parentJobId: Option[String],
      label: String
  )

  /** One attempt (execution) of a job. */
  final case class AttemptRecord(
      attemptId: String,
      jobId: String,
      startedAtMs: Long,
      endedAtMs: Option[Long]
  )

  /** Execution result of a completed episode. */
  enum ExecutionStatus:
    case Succeeded
    case Failed

  /** Explicit terminal state of an interrupted/cancelled episode (EXPORT-03).
    * `Uncertain` = interrupted without definitive terminal evidence; it is
    * exported as-is, never upgraded to a completion.
    */
  enum TerminalState:
    case Cancelled
    case Interrupted
    case Uncertain

  /** Terminal evidence of one episode (one attempt execution). A partial
    * episode carries its explicit terminal state plus a reason; the export
    * keeps it partial forever — a cancellation is never rewritten into a fake
    * completion.
    */
  enum Completion:
    case Completed(outcome: ExecutionStatus, atMs: Long)
    case Partial(terminal: TerminalState, reason: String, atMs: Long)

  /** Status carried by task/safety/protocol outcome evidence. */
  enum OutcomeStatus:
    case Pass
    case Fail
    case Error

  /** Safety/protocol outcome evidence. Exported only when present; episodes
    * without evidence export the outcome as explicitly `missing` with a reason
    * — coverage is reported, never silently skipped.
    */
  final case class OutcomeEvidence(
      status: OutcomeStatus,
      reason: Option[String],
      evidenceSha256: Option[String]
  )

  /** Verdict value produced by an actual verifier run. */
  enum VerdictValue:
    case Pass
    case Fail
    case Error

  /** An ACTUAL verifier result (protected deterministic verifier; yabanin
    * integration contract §11). This is the ONLY source of verdict records: the
    * model never records its own success, and the exporter refuses to attach a
    * verdict to an episode that did not declare a verifier.
    */
  final case class VerifierResult(
      episodeId: String,
      verifierId: String,
      verdict: VerdictValue,
      atMs: Long,
      /** sha256 over the verifier evidence artifact (e.g. verify.sh stdout).
        */
      evidenceSha256: String,
      evidenceKind: String,
      note: Option[String]
  )

  /** Usage with honest unknown-ness. Unknown prices stay null AND are marked
    * `unknown`; `unknown=false` with a null observed price is a typed
    * [[ExportError.BadUsage]] (never fabricated numbers, never 0-conflation).
    */
  final case class Usage(
      reservedMicroUsd: Option[Long],
      observedMicroUsd: Option[Long],
      unknown: Boolean
  )

  /** One model call of an attempt. */
  final case class ModelCallInput(
      callId: String,
      attemptId: String,
      round: Int,
      model: String,
      atMs: Long,
      usage: Usage
  )

  /** Artifact REFERENCE: hashes only, content never travels in the export. */
  final case class ArtifactRef(
      artifactId: String,
      sha256: String,
      mediaType: String,
      sizeBytes: Long
  )

  /** Actual tool-call result evidence. A refusal is `Denied` and must carry its
    * deny reason (yabanin integration contract §11: refusals include
    * `deny_reason`).
    */
  enum ToolResult:
    case Succeeded
    case Failed(reason: String)
    case Denied(denyReason: String)
    case Cancelled
    case Timeout
    case Uncertain(reason: String)

  /** One tool call of an attempt.
    *
    * `argsJsonText` is the raw argument text as received from the model. It
    * exists ONLY as digest input — the exporter refuses to embed it. Embedding
    * requires `argsRedactedSafe`: args the caller explicitly pins as
    * redacted-safe (exported verbatim as a JSON object). At least one of the
    * two must be present for a digest to exist; with both present the digest is
    * computed over the raw text.
    */
  final case class ToolCallInput(
      toolCallId: String,
      attemptId: String,
      tool: String,
      /** Model-call id that issued this tool call, when attributable. */
      callId: Option[String],
      atMs: Long,
      result: ToolResult,
      argsJsonText: Option[String],
      argsRedactedSafe: Option[String],
      /** Environment variable NAME references only (no values, ever). */
      envRefs: Vector[String],
      artifacts: Vector[ArtifactRef]
  )

  /** Late-arriving usage evidence for an already-exported call (EXPORT-03).
    * Applied through [[Reconciliation.append]], which appends a VERSIONED
    * reconciliation record and leaves every prior document byte-identical.
    */
  final case class LateUsage(
      callId: String,
      usage: Usage,
      source: String,
      atMs: Long
  )

  /** One episode of the export: the execution record of one attempt. */
  final case class EpisodeInput(
      episodeId: String,
      attemptId: String,
      completion: Completion,
      /** Whether a verifier was declared for this episode's task. Episodes with
        * `verifierExpected=true` but no actual [[VerifierResult]] export a
        * PENDING verifier state and a `missing` task outcome — a verdict is
        * never fabricated (EXPORT-02).
        */
      verifierExpected: Boolean,
      /** Declared verifier id; required iff `verifierExpected`. */
      verifierId: Option[String],
      safety: Option[OutcomeEvidence],
      protocol: Option[OutcomeEvidence]
  )

  /** The full input of one export. Structure and cross-references are validated
    * first ([[Exporter.export]] reports typed [[ExportError]] values); nothing
    * is partially exported.
    */
  final case class EvalRunInput(
      run: RunInfo,
      jobs: Vector[JobRecord],
      attempts: Vector[AttemptRecord],
      episodes: Vector[EpisodeInput],
      calls: Vector[ModelCallInput],
      tools: Vector[ToolCallInput],
      verdicts: Vector[VerifierResult]
  )

  /** Typed failure of the export surface. Export is all-or-nothing: a
    * validation failure produces NO documents, and a broken input is data the
    * caller can inspect, never a best-effort guess.
    */
  enum ExportError:
    case UnsupportedSchemaVersion(found: Int, expected: Int)
    case BadGeneratorName(name: String)
    case EmptyField(field: String, where: String)
    case DuplicateId(kind: String, id: String)
    case MultipleRootJobs(count: Int)
    case ParentNotEarlier(child: String, parent: String)
    case AttemptWithoutEpisode(attemptId: String)

    case AttemptJobMismatch(
        attemptId: String,
        expectedJobId: String,
        actualJobId: String
    )

    case DanglingReference(
        from: String,
        id: String,
        missing: String,
        kind: String
    )

    case VerifierMismatch(episodeId: String, reason: String)
    case BadSha256(field: String, value: String)
    case BadEnvRef(value: String)
    case BadToolArgs(toolCallId: String, reason: String)
    case BadUsage(reason: String)
    case MissingCall(callId: String)
    case DecodeFailure(where: String, detail: String)
    case BundleMissingFile(name: String)
    case IntegrityMismatch(file: String, expected: String, actual: String)
    case ChainViolation(reason: String)

    def message: String = this match
      case UnsupportedSchemaVersion(found, expected) =>
        s"unsupported schemaVersion $found (this reader pins $expected)"
      case BadGeneratorName(name) =>
        s"generator.name must be 'raider', got '$name'"
      case EmptyField(field, where) =>
        s"empty required field '$field' in $where"
      case DuplicateId(kind, id) => s"duplicate $kind id '$id'"
      case MultipleRootJobs(count) =>
        s"exactly one root job is required, found $count"
      case ParentNotEarlier(child, parent) =>
        s"parent job '$parent' must appear before child job '$child'"
      case AttemptWithoutEpisode(attemptId) =>
        s"attempt '$attemptId' has no episode record (1:1 episode-attempt required)"
      case AttemptJobMismatch(attemptId, expectedJobId, actualJobId) =>
        s"attempt '$attemptId' belongs to job '$actualJobId', not '$expectedJobId'"
      case DanglingReference(from, id, missing, kind) =>
        s"$from '$id' references missing $kind '$missing'"
      case VerifierMismatch(episodeId, reason) =>
        s"verifier mismatch on episode '$episodeId': $reason"
      case BadSha256(field, value) =>
        s"field '$field' is not a lowercase 64-hex sha256: '$value'"
      case BadEnvRef(value) =>
        s"env reference must be a NAME (no value), got '$value'"
      case BadToolArgs(toolCallId, reason) =>
        s"bad tool args for '$toolCallId': $reason"
      case BadUsage(reason)          => s"bad usage: $reason"
      case MissingCall(callId)       => s"no exported model call '$callId'"
      case DecodeFailure(where, msg) => s"cannot decode $where: $msg"
      case BundleMissingFile(name)   => s"export bundle is missing '$name'"
      case IntegrityMismatch(file, expected, actual) =>
        s"integrity mismatch for '$file': manifest pins $expected, actual $actual"
      case ChainViolation(reason) => s"reconciliation chain violation: $reason"

  end ExportError

end model
