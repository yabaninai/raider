package raider.cli.chat

import raider.core.RaiderError
import raider.dsl.workflow.{
  NamedCheck as DslNamedCheck,
  StsAgentStage,
  StsOrigin,
  StsParam,
  StsSelection,
  StsTurn,
  StsTurnKind,
  StsTurnStatus
}

/** RAI-160: the TaskSelection-to-StsSelection adapter — the missing link
  * between the RAI-155.b chat selection (`TaskCommands.select`) and the
  * RAI-155.c renderer input (`StsSelection`, ADR-033 §4).
  *
  * Pure 1:1 mapping, no invention (ADR-033 §2.5 discipline):
  *
  *   - turn kind/status strings map onto the dsl enums 1:1 (an unknown string
  *     is a typed refusal, never silently coerced);
  *   - the turn text is the BOUNDED captured request
  *     (`TurnLedger.boundedRequest`, applied at capture and re-applied here —
  *     payload-free by the §5.1 ledger contract: request text only, never tool
  *     results or model answers);
  *   - named checks map argv-identically (model text can never add or alter a
  *     check — they exist only through user registration);
  *   - the agent stage bound flows 1:1 from the captured `maxRoundsBound`
  *     (reference only — the launcher binds the effective policy at run time);
  *   - the externalized task params (`targetPath`, `summary`) are NOT invented
  *     from captured text: they are explicit inputs of the export command
  *     (ADR-033 §4.1 — parameters vary per run and are never hard-coded from
  *     one run's captured values);
  *   - the origin is built ONLY from non-secret config references (provider
  *     kind/model/base URL; credentials never travel here).
  *
  * The R4 secret scan runs AGAIN at this boundary (defense in depth): the
  * selection was validated at select time, but select and export are separate
  * user actions — the adapter refuses credential-shaped material in the
  * snapshot independently of when/how the snapshot was built. The matched value
  * is never echoed (ADR-033 §8 R4).
  */
object TaskWorkflowAdapter:

  /** Chat transport names -> STS origin provider kinds (ADR-033 §6.4). The two
    * first-class transports map honestly; the mock/fallback transport cannot be
    * declared as an export origin — that is a typed refusal, never a mislabeled
    * origin.
    */
  def chatProviderKind(provider: String): Either[RaiderError, String] =
    provider match
      case "openai"    => Right("openai-compatible")
      case "anthropic" => Right("anthropic-compatible")
      case other =>
        Left(
          RaiderError.InputValidation(
            s"CWF-R1: this session ran on the '$other' transport; an export " +
              "origin must declare a first-class transport (openai-compatible " +
              "or anthropic-compatible) — re-run the task in a chat session " +
              "started with --provider openai or --provider anthropic"
          )
        )

  /** Non-secret origin references from the live session (ADR-033 §5.1
    * configRefs discipline): provider kind, model name, optional http(s) base
    * URL. A blank base URL exports as `None` (the generated launcher then
    * carries an empty editable constant the user sets before running).
    */
  def toStsOrigin(
      chatProvider: String,
      model: String,
      baseUrl: String
  ): Either[RaiderError, StsOrigin] =
    for
      kind <- chatProviderKind(chatProvider)
      _ <-
        if model.trim.isEmpty then
          Left(
            RaiderError.InputValidation(
              "CWF-R1: the session has no model reference; the export origin " +
                "needs the non-secret model name"
            )
          )
        else Right(())
    yield StsOrigin(
      providerKind = kind,
      model = model,
      baseUrl = Option(baseUrl).map(_.trim).filter(_.nonEmpty)
    )

  private def kindOf(
      raw: String,
      turnId: String
  ): Either[RaiderError, StsTurnKind] =
    raw match
      case TurnLedger.Kind.Intent     => Right(StsTurnKind.Intent)
      case TurnLedger.Kind.Refinement => Right(StsTurnKind.Refinement)
      case TurnLedger.Kind.Ordinary   => Right(StsTurnKind.Ordinary)
      case other =>
        Left(
          RaiderError.InputValidation(
            s"CWF-R1: turn '$turnId' carries unknown kind '$other'; the " +
              "ledger kinds are Intent/Refinement/Ordinary"
          )
        )

  private def statusOf(
      raw: String,
      turnId: String
  ): Either[RaiderError, StsTurnStatus] =
    raw match
      case TurnLedger.Status.Completed   => Right(StsTurnStatus.Completed)
      case TurnLedger.Status.Failed      => Right(StsTurnStatus.Failed)
      case TurnLedger.Status.Interrupted => Right(StsTurnStatus.Interrupted)
      case TurnLedger.Status.Unknown     => Right(StsTurnStatus.Unknown)
      case other =>
        Left(
          RaiderError.InputValidation(
            s"CWF-R1: turn '$turnId' carries unknown status '$other'; the " +
              "ledger statuses are Completed/Failed/Interrupted/Unknown"
          )
        )

  /** One turn, 1:1: stable id, explicit kind/status, bounded request text. */
  def toStsTurn(t: TurnRecord): Either[RaiderError, StsTurn] =
    for
      kind <- kindOf(t.kind, t.turnId)
      status <- statusOf(t.status, t.turnId)
    yield StsTurn(
      turnId = t.turnId,
      kind = kind,
      status = status,
      text = TurnLedger.boundedRequest(t.request)
    )

  private def sequence[A, B](
      items: List[A]
  )(f: A => Either[RaiderError, B]): Either[RaiderError, List[B]] =
    items.foldLeft[Either[RaiderError, List[B]]](Right(List.empty)) {
      (acc, item) =>
        for
          tail <- acc
          head <- f(item)
        yield tail :+ head
    }

  /** The full adapter: a validated selection snapshot plus the explicit
    * externalized params and origin references become the renderer input.
    * Deterministic and pure: the same inputs produce the same `StsSelection`
    * (§4.3 — the renderer downstream is a pure function of this value).
    */
  def toStsSelection(
      sessionId: String,
      origin: StsOrigin,
      selection: TaskSelection,
      taskParams: List[StsParam]
  ): Either[RaiderError, StsSelection] =
    // R4 at the adapter boundary (ADR-033 §6.5/§8): independent of select-time
    // validation — the snapshot may predate a capture or be built directly.
    TurnLedger.secretHit(selection.turns, selection.checks) match
      case Some(where) =>
        Left(
          RaiderError.InputValidation(
            s"CWF-R4: the selection contains credential-shaped material in " +
              s"$where; remove the secret and re-capture the turn — the value " +
              "is never displayed"
          )
        )
      case None =>
        for
          intent <- toStsTurn(selection.intent)
          refinements <- sequence(selection.refinements)(toStsTurn)
          _ <-
            if sessionId.trim.isEmpty then
              Left(
                RaiderError.InputValidation(
                  "CWF-R1: empty session reference; the export provenance " +
                    "needs the exporting session's id"
                )
              )
            else Right(())
        yield StsSelection(
          sessionId = sessionId,
          intent = intent,
          refinements = refinements,
          taskParams = taskParams,
          checks = selection.checks.map(c => DslNamedCheck(c.name, c.argv)),
          agentStage = StsAgentStage(selection.maxRoundsBound),
          origin = origin
        )

end TaskWorkflowAdapter
