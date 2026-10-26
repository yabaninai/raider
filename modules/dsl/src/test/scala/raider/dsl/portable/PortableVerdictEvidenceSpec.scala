package raider.dsl.portable

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import zio.json.*
import zio.json.ast.Json
import zio.test.*
import zio.test.Assertion.*

/** Pins the typed verdict-evidence origin resolution (RAI-212) against the
  * FROZEN corpus: every classified document is first validated (corpus
  * honesty), and the frozen corpus fixtures themselves carry the adversarial
  * shapes — bindings aiming at agent/composite steps while `verdict.from`
  * decorates a trusted check with `trustedVerifier` authority. The launcher
  * must claim verdict authority ONLY for
  * [[PortableVerdictEvidence.TrustedCheck]] evidence, and must record any
  * verdict-shaped assertion made outside it verbatim (contradictory) without
  * ever letting the value gate the exit code.
  */
object PortableVerdictEvidenceSpec extends ZIOSpecDefault:

  private def repoRoot: Path =
    val cwd = Paths.get("").toAbsolutePath
    Iterator
      .iterate(Option(cwd))(opt => opt.flatMap(c => Option(c.getParent)))
      .takeWhile(_.isDefined)
      .collect { case Some(dir) => dir }
      .find(dir => Files.isRegularFile(dir.resolve("build.sbt")))
      .getOrElse(cwd)

  private def fixture(relative: String): Json.Obj =
    Files
      .readString(repoRoot.resolve(relative), StandardCharsets.UTF_8)
      .fromJson[Json]
      .toOption
      .get
      .asInstanceOf[Json.Obj]

  private def validated(doc: Json) =
    PortableWorkflowValidator.validate(
      doc.toJson.getBytes(StandardCharsets.UTF_8)
    )

  private def classify(doc: Json): Option[PortableVerdictEvidence.Origin] =
    PortableVerdictEvidence.originOf(doc)

  /** Rebuilds `outputs` so both bindings and verdict.from point at `pointer`
    * (the launch-declared shape under the frozen validator: every declared
    * verdict carries the literal trustedVerifier authority).
    */
  private def outputsPointingAt(doc: Json.Obj, pointer: String): Json.Obj =
    Json.Obj(
      doc.fields.map {
        case ("outputs", _) =>
          (
            "outputs",
            Json.Obj(
              "bindings" -> Json.Obj("result" -> Json.Str(pointer)),
              "verdict" -> Json.Obj(
                "from" -> Json.Str(pointer),
                "authority" -> Json.Str("trustedVerifier")
              )
            )
          )
        case other => other
      }
    )

  def spec =
    suite("PortableVerdictEvidence")(
      suite("frozen corpus origins")(
        test(
          "trusted-check.json: bindings aim at the check directly -> trusted-check evidence"
        ) {
          val doc =
            fixture("contracts/workflow-portable/valid/trusted-check.json")
          assert(validated(doc))(isRight) &&
          assert(classify(doc))(
            isSome(
              equalTo(
                PortableVerdictEvidence.TrustedCheck(
                  List("verify")
                ): PortableVerdictEvidence.Origin
              )
            )
          )
        },
        test(
          "parity acceptance fixture: sequence ending at the check keeps trusted-check evidence (the pinned green claim)"
        ) {
          val doc = fixture("contracts/acceptance/portable/parity-verdict.json")
          assert(validated(doc))(isRight) &&
          assert(classify(doc))(
            isSome(
              equalTo(
                PortableVerdictEvidence.TrustedCheck(
                  List("run", "gate")
                ): PortableVerdictEvidence.Origin
              )
            )
          )
        },
        test(
          "agent.json: bindings aim at the agent while verdict.from decorates a check -> the root output is AGENT evidence despite the declared trustedVerifier authority"
        ) {
          val doc = fixture("contracts/workflow-portable/valid/agent.json")
          assert(validated(doc))(isRight) &&
          assert(classify(doc))(
            isSome(
              equalTo(
                PortableVerdictEvidence.AgentStep(
                  List("summarize")
                ): PortableVerdictEvidence.Origin
              )
            )
          )
        },
        test(
          "join.json: bindings aim at an all-agent join -> agent evidence"
        ) {
          val doc = fixture("contracts/workflow-portable/valid/join.json")
          assert(validated(doc))(isRight) &&
          assert(classify(doc))(
            isSome(
              equalTo(
                PortableVerdictEvidence.AgentStep(
                  List("together", "left", "right")
                ): PortableVerdictEvidence.Origin
              )
            )
          )
        },
        test(
          "conditional.json: both branches agents -> agent evidence"
        ) {
          val doc =
            fixture("contracts/workflow-portable/valid/conditional.json")
          assert(validated(doc))(isRight) &&
          assert(classify(doc))(
            isSome(
              equalTo(
                PortableVerdictEvidence.AgentStep(
                  List("branch", "yes", "no")
                ): PortableVerdictEvidence.Origin
              )
            )
          )
        },
        test(
          "bounded-map.json: map over an agent template -> agent evidence (the output is the aggregate array)"
        ) {
          val doc =
            fixture("contracts/workflow-portable/valid/bounded-map.json")
          assert(validated(doc))(isRight) &&
          assert(classify(doc))(
            isSome(
              equalTo(
                PortableVerdictEvidence.AgentStep(
                  List("per-item", "summarize-item")
                ): PortableVerdictEvidence.Origin
              )
            )
          )
        },
        test(
          "checkpoint.json: bindings aim at a checkpoint -> mixed evidence (caller-supplied program, not a pinned check)"
        ) {
          val doc = fixture("contracts/workflow-portable/valid/checkpoint.json")
          assert(validated(doc))(isRight) &&
          assert(classify(doc))(
            isSome(
              equalTo(
                PortableVerdictEvidence.Mixed(
                  List("approval")
                ): PortableVerdictEvidence.Origin
              )
            )
          )
        },
        test(
          "repair.json: bindings aim at a repair -> mixed evidence"
        ) {
          val doc = fixture("contracts/workflow-portable/valid/repair.json")
          assert(validated(doc))(isRight) &&
          assert(classify(doc))(
            isSome(
              equalTo(
                PortableVerdictEvidence.Mixed(
                  List("repair-edit")
                ): PortableVerdictEvidence.Origin
              )
            )
          )
        }
      ),
      suite("mixed compositions (corpus mutations)")(
        test(
          "join mixing an agent and the check -> mixed evidence even though a real check feeds the join"
        ) {
          val mutated = Json.Obj(
            fixture(
              "contracts/workflow-portable/valid/join.json"
            ).fields.map {
              case ("steps", Json.Arr(values)) =>
                (
                  "steps",
                  Json.Arr(
                    values.map {
                      case s: Json.Obj
                          if s.fields.exists(
                            _ == ("id", Json.Str("together"))
                          ) =>
                        Json.Obj(
                          s.fields.map {
                            case ("steps", _) =>
                              (
                                "steps",
                                Json.Arr(
                                  zio.Chunk(
                                    Json.Str("left"),
                                    Json.Str("verify")
                                  )
                                )
                              )
                            case other => other
                          }
                        )
                      case other => other
                    }
                  )
                )
              case other => other
            }
          )
          assert(validated(mutated))(isRight) &&
          assert(classify(mutated))(
            isSome(
              equalTo(
                PortableVerdictEvidence.Mixed(
                  List("together", "left", "verify")
                ): PortableVerdictEvidence.Origin
              )
            )
          )
        },
        test(
          "conditional with an agent then-branch and the check as else -> mixed evidence (statically unattributable)"
        ) {
          val doc = Json.Obj(
            fixture(
              "contracts/workflow-portable/valid/conditional.json"
            ).fields.map {
              case ("steps", Json.Arr(values)) =>
                (
                  "steps",
                  Json.Arr(
                    values.map {
                      case s: Json.Obj
                          if s.fields.exists(_ == ("id", Json.Str("branch"))) =>
                        Json.Obj(
                          s.fields.map {
                            case ("else", _) => ("else", Json.Str("verify"))
                            case other       => other
                          }
                        )
                      case other => other
                    }
                  )
                )
              case other => other
            }
          )
          assert(validated(doc))(isRight) &&
          assert(classify(doc))(
            isSome(
              equalTo(
                PortableVerdictEvidence.Mixed(
                  List("branch", "yes", "verify")
                ): PortableVerdictEvidence.Origin
              )
            )
          )
        },
        test(
          "map over a check template: the frozen validator makes this UNEXPRESSIBLE (map-local #/item pointers are agent-template-only), so the launcher never dispatches such a document"
        ) {
          val base =
            fixture("contracts/workflow-portable/valid/bounded-map.json")
          val checkTemplate = Json.Obj(
            base.fields.map {
              case ("steps", Json.Arr(values)) =>
                val updated = values.map {
                  case s: Json.Obj
                      if s.fields.exists(
                        _ == ("id", Json.Str("per-item"))
                      ) =>
                    Json.Obj(
                      s.fields.map {
                        case ("templateStep", _) =>
                          ("templateStep", Json.Str("check-item"))
                        case other => other
                      }
                    )
                  case other => other
                }
                val checkItem = Json.Obj(
                  "id" -> Json.Str("check-item"),
                  "kind" -> Json.Str("trustedCheck"),
                  "checkId" -> Json.Str("result-count"),
                  "registrySnapshot" -> Json.Str(
                    "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
                  ),
                  "input" -> Json.Str("#/item"),
                  "outputSchema" -> Json.Str("results")
                )
                ("steps", Json.Arr(updated ++ zio.Chunk(checkItem)))
              case other => other
            }
          )
          // the corpus constraint itself (validator refuses the template):
          assert(validated(checkTemplate))(isLeft) &&
          // the resolver still classifies such a document defensively (totality
          // over documents that bypass validation): never a claim path
          assert(classify(checkTemplate))(
            isSome(
              equalTo(
                PortableVerdictEvidence.Mixed(
                  List("per-item", "check-item")
                ): PortableVerdictEvidence.Origin
              )
            )
          )
        }
      ),
      suite("recorded value refinement")(
        test(
          "a trusted-check origin's passed value claims the verdict; the code stays trusted-check"
        ) {
          val recorded = PortableVerdictEvidence.record(
            PortableVerdictEvidence.TrustedCheck(List("verify")),
            Json.Obj("status" -> Json.Str("passed"))
          )
          assertTrue(
            recorded.code == "trusted-check",
            recorded.assertedStatus == Some("passed"),
            recorded.claimsTrustedVerdict
          )
        },
        test(
          "a trusted-check origin's non-passed value never claims and stays veto-capable (RA-OUT discipline, unchanged)"
        ) {
          val recorded = PortableVerdictEvidence.record(
            PortableVerdictEvidence.TrustedCheck(List("verify")),
            Json.Obj("status" -> Json.Str("failed"))
          )
          assertTrue(
            recorded.code == "trusted-check",
            recorded.assertedStatus == Some("failed"),
            !recorded.claimsTrustedVerdict
          )
        },
        test(
          "an agent origin asserting status passed is recorded contradictory VERBATIM and never claims"
        ) {
          val recorded = PortableVerdictEvidence.record(
            PortableVerdictEvidence.AgentStep(List("prepare")),
            Json.Obj("status" -> Json.Str("passed"), "text" -> Json.Str("hi"))
          )
          assertTrue(
            recorded.code == "contradictory",
            recorded.assertedStatus == Some("passed"),
            !recorded.claimsTrustedVerdict
          )
        },
        test(
          "a mixed origin asserting a failing status is contradictory too — the value can neither claim nor veto"
        ) {
          val recorded = PortableVerdictEvidence.record(
            PortableVerdictEvidence.Mixed(List("combined")),
            Json.Obj("status" -> Json.Str("failed"))
          )
          assertTrue(
            recorded.code == "contradictory",
            recorded.assertedStatus == Some("failed"),
            !recorded.claimsTrustedVerdict
          )
        },
        test(
          "data output (no status) keeps the origin's own code; non-string or nested status fields are not assertions"
        ) {
          val data = PortableVerdictEvidence.record(
            PortableVerdictEvidence.AgentStep(List("prepare")),
            Json.Obj("text" -> Json.Str("[mock] answer"))
          )
          val numeric = PortableVerdictEvidence.record(
            PortableVerdictEvidence.AgentStep(List("prepare")),
            Json.Obj("status" -> Json.Num(42))
          )
          val array = PortableVerdictEvidence.record(
            PortableVerdictEvidence.AgentStep(List("prepare")),
            Json.Arr(zio.Chunk(Json.Obj("status" -> Json.Str("passed"))))
          )
          assertTrue(
            data.code == "agent-step",
            data.assertedStatus.isEmpty,
            numeric.code == "agent-step",
            numeric.assertedStatus.isEmpty,
            array.code == "agent-step",
            array.assertedStatus.isEmpty
          )
        }
      ),
      test(
        "evidenceJson renders the recorded shape for the run.json splice (origin, target verbatim, step path, asserted status only when observed)"
      ) {
        val contradicted = PortableVerdictEvidence.evidenceJson(
          PortableVerdictEvidence
            .record(
              PortableVerdictEvidence.AgentStep(List("prepare")),
              Json.Obj("status" -> Json.Str("passed"))
            ),
          "#/steps/prepare/output"
        )
        val structural = PortableVerdictEvidence.evidenceJson(
          PortableVerdictEvidence.record(
            PortableVerdictEvidence.TrustedCheck(List("run", "gate")),
            Json.Null
          ),
          "#/steps/run/output"
        )
        assertTrue(
          contradicted.toJson == """{"origin":"contradictory","target":"#/steps/prepare/output","steps":["prepare"],"assertedStatus":"passed"}""",
          structural.toJson == """{"origin":"trusted-check","target":"#/steps/run/output","steps":["run","gate"]}"""
        )
      }
    )
