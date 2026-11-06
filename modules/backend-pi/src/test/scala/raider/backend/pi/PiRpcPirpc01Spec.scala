package raider.backend.pi

import raider.backend.pi.stand.{PiRpcStand, StandHandle}
import raider.core.*
import zio.*
import zio.json.*
import zio.json.ast.Json

/** PIRPC-01: one complete prompt + tool run driven through a REAL subprocess
  * stand (java child speaking the pi RPC JSONL protocol). Verifies event
  * normalization, delta accumulation vs the authoritative message_end content,
  * tool-call correlation, usage observation with honest pricing, version
  * recording, and orderly child shutdown.
  */
object PiRpcPirpc01Spec:

  def main(args: Array[String]): Unit = PiSpecSupport.runUnsafe(spec)

  private def spec: ZIO[Any, Throwable, Unit] =
    ZIO
      .scoped {
        for
          cfg <- ZIO
            .fromEither(
              PiRpcConfig.make(
                command = StandHandle.argv("prompt-tool-run"),
                versionCommand = StandHandle.versionArgv(),
                expectedVersion = Some(PiRpcStand.DefaultVersion)
              )
            )
            .mapError(e => new RuntimeException(e.detail))
          backend <- PiRpcBackend.make(cfg)
          vi <- backend.probeVersion
          _ <- ZIO.attempt(println(s"[version-recorded] ${vi.line}"))
          run <- backend.run("Run the checks")
          events <- run.events.runCollect
        yield (vi, events, run)
      }
      .timeout(60.seconds)
      .flatMap {
        case None =>
          ZIO.attempt(
            PiSpecSupport
              .check("pirpc01 completes within 60s")(false, "timeout")
          )
        case Some((vi, events, run)) =>
          ZIO.attempt(verify(vi, events)) *> lifecycleChecks(run)
      }

  private def verify(vi: PiVersionInfo, events: Iterable[ModelEvent]): Unit =
    val es = events.toList

    PiSpecSupport.check("probeVersion records the child version line")(
      vi.line == PiRpcStand.DefaultVersion,
      s"version line: ${vi.line}"
    )

    val attempt = es.headOption
    PiSpecSupport.check("first event is Started with the request attempt id")(
      attempt.exists(_.isInstanceOf[ModelEvent.Started]) &&
        attempt.get
          .asInstanceOf[ModelEvent.Started]
          .attempt
          .value
          .startsWith("r-"),
      s"head: $attempt"
    )
    val attemptId = attempt
      .collect { case ModelEvent.Started(a) => a }
      .getOrElse(AttemptId("missing"))

    val deltas = es.collect { case ModelEvent.TextDelta(_, text) => text }
    PiSpecSupport.check("text deltas stream incrementally (2 fragments)")(
      deltas.size == 2,
      s"deltas: $deltas"
    )
    val accumulated = deltas.mkString
    PiSpecSupport.check("accumulated text equals expected assistant text")(
      accumulated == "Ran the check.",
      s"accumulated: $accumulated"
    )

    // Authoritative message_end content must agree with the delta stream.
    val toolMsgEnd = es.collectFirst {
      case ModelEvent.MetadataObserved(_, "pi.message.end", v)
          if v.contains("\"toolUse\"") =>
        v
    }
    val textFromAuthoritative = toolMsgEnd.flatMap { v =>
      v.fromJson[Json].toOption.flatMap { j =>
        j.asObject.flatMap(_.get("content")).flatMap(_.asArray).map { arr =>
          arr.toSeq.collect {
            case Json.Obj(f)
                if f
                  .find(_._1 == "type")
                  .exists(_._2.asString.contains("text")) =>
              f.find(_._1 == "text").flatMap(_._2.asString).getOrElse("")
          }.mkString
        }
      }
    }
    PiSpecSupport.check(
      "delta accumulation equals authoritative message_end content"
    )(
      textFromAuthoritative.contains(accumulated),
      s"authoritative=$textFromAuthoritative accumulated=$accumulated"
    )

    val ready = es.collectFirst {
      case m @ ModelEvent.ToolCallReady(_, _, _, _) => m
    }
    PiSpecSupport.check("tool call is ready exactly once")(
      ready.isDefined && es.count(
        _.isInstanceOf[ModelEvent.ToolCallReady]
      ) == 1,
      s"ready: $ready"
    )
    ready.foreach { case ModelEvent.ToolCallReady(_, call, name, argsJson) =>
      PiSpecSupport.check("tool call id/name normalized from toolCall block")(
        call == ToolCallId("call_1") && name == "proc_run",
        s"call=$call name=$name"
      )
      PiSpecSupport.check("tool arguments JSON is complete and exact")(
        argsJson == """{"command":"make test"}""",
        s"argsJson=$argsJson"
      )
    }

    val toolDeltas = es.collect {
      case ModelEvent.ToolCallDelta(_, _, _, frag) => frag
    }
    PiSpecSupport.check(
      "tool argument fragments concatenate to the complete JSON"
    )(
      toolDeltas.mkString == """{"command":"make test"}""" && toolDeltas.size == 2,
      s"fragments=$toolDeltas"
    )
    PiSpecSupport.check(
      "tool argument fragments agree with ToolCallReady arguments"
    )(
      ready.exists { case ModelEvent.ToolCallReady(_, _, _, args) =>
        args == toolDeltas.mkString
      },
      s"ready=$ready fragments=${toolDeltas.mkString}"
    )

    val usage = es.collectFirst { case ModelEvent.UsageObserved(_, u, pk) =>
      (u, pk)
    }
    PiSpecSupport.check(
      "usage observed with pi token counts and honest pricing"
    )(
      usage.exists((u, pk) =>
        u.inputTokens == 120L && u.outputTokens == 34L && !pk
      ),
      s"usage=$usage"
    )
    val costMeta = es.collectFirst {
      case ModelEvent.MetadataObserved(_, "pi.usage.cost.usd", v) => v
    }
    PiSpecSupport.check(
      "pi-reported cost recorded as metadata, not priced usage"
    )(
      costMeta.contains("0.0011"),
      s"costMeta=$costMeta"
    )

    val toolExecKeys = es.collect {
      case ModelEvent.MetadataObserved(_, k, v)
          if k.startsWith("pi.toolExecution.") =>
        (k, v)
    }
    PiSpecSupport.check(
      "tool execution lifecycle observed as metadata (pi owns execution)"
    )(
      toolExecKeys.exists(_._1 == "pi.toolExecution.start") &&
        toolExecKeys.exists(_._1 == "pi.toolExecution.update") &&
        toolExecKeys.exists(_._1 == "pi.toolExecution.end") &&
        toolExecKeys.forall(_._2.contains("call_1")),
      s"toolExecKeys=$toolExecKeys"
    )

    val turnMeta = es.collect {
      case ModelEvent.MetadataObserved(_, k, _) if k.startsWith("pi.turn.") => k
    }
    PiSpecSupport.check("turn lifecycle recorded as metadata")(
      turnMeta.contains("pi.turn.start") && turnMeta.contains("pi.turn.end"),
      s"turnMeta=$turnMeta"
    )

    PiSpecSupport.check(
      "no agent_start-based completion: agent_end is metadata only"
    )(
      es.exists {
        case ModelEvent.MetadataObserved(_, k, _) => k == "pi.agent.end";
        case _                                    => false
      },
      s"events=${es.map(m => m.getClass.getSimpleName).mkString(",")}"
    )

    PiSpecSupport.check("stream terminates with Finished(settled)")(
      es.lastOption.exists {
        case ModelEvent.Finished(_, reason) => reason == "settled"
        case _                              => false
      },
      s"last=${es.lastOption}"
    )
    PiSpecSupport.check("exactly one terminal event")(
      es.count {
        case _: ModelEvent.Finished => true
        case _: ModelEvent.Failed   => true
        case _                      => false
      } == 1,
      s"terminals=${es.count(e =>
          e.isInstanceOf[ModelEvent.Finished] || e.isInstanceOf[ModelEvent.Failed]
        )}"
    )

  /** Child lifecycle after the owning scope closed: the child must have fully
    * exited (orderly stdin-EOF exit, not force-kill).
    */
  private def lifecycleChecks(run: PiRpcRun): ZIO[Any, Throwable, Unit] =
    ZIO.sleep(400.millis) *>
      run.childAlive.flatMap { alive =>
        ZIO.attempt(
          PiSpecSupport.check("child process fully exited after scope close")(
            !alive,
            s"childAlive=$alive"
          )
        )
      } *>
      run.childExitCode.flatMap { code =>
        ZIO.attempt(
          PiSpecSupport.check("child exited orderly (exit code 0, stdin EOF)")(
            code.contains(0),
            s"exitCode=$code"
          )
        )
      }
