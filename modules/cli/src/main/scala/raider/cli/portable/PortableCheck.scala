package raider.cli.portable

import raider.cli.run.HeadlessRunner
import raider.core.RaiderError

import zio.json.*
import zio.json.ast.Json

import java.nio.file.Paths

/** `raider workflow check --doc <file>` (RAI-199; ADR-036): the ZERO-DISPATCH
  * document gate. Reads the bytes, runs the frozen v1.0 validation and prints a
  * closed machine-readable verdict on stdout — no backend, no admission, no
  * trusted registry, no model/tool dispatch by construction.
  *
  * Exit codes (frozen §8 mapping): 0 valid; 21 (RA-CFG/RA-INP) for argument
  * errors, unreadable files and every preflight diagnostic. A green check is a
  * LOCAL statement only — it never implies launch authority.
  */
object PortableCheck:

  def command(argv: List[String]): Int =
    parse(argv) match
      case Left(err) =>
        System.err.println(s"raider ${err.code}: ${err.detail}")
        HeadlessRunner.exitCodeFor(err)
      case Right(doc) =>
        PortableDoc.readBytes(doc) match
          case Left(err) =>
            // IO-level failure: typed RA error on stderr, the closed
            // diagnostic shape on stdout (one format for all consumers).
            System.err.println(s"raider ${err.code}: ${err.detail}")
            System.out.println(
              s"""{"valid":false,"diagnostic":${Json
                  .Obj(
                    "code" -> Json.Str(err.code),
                    "documentPath" -> Json.Str(doc.toString),
                    "stepId" -> Json.Null,
                    "offendingRef" -> Json.Null,
                    "suggestedCorrection" -> Json.Str(err.detail)
                  )
                  .toJson}}"""
            )
            System.out.flush()
            HeadlessRunner.exitCodeFor(err)
          case Right(bytes) =>
            PortableDoc.validate(bytes) match
              case Left(diag) =>
                System.err.println(
                  s"raider RA-INP: ${PortableDoc.diagnosticLine(diag)}"
                )
                System.out.println(
                  s"""{"valid":false,"diagnostic":${PortableDoc
                      .diagnosticJson(diag)
                      .toJson}}"""
                )
                System.out.flush()
                raider.core.result.ExitCodes.ConfigInvalid
              case Right(_) =>
                System.out.println(
                  s"""{"valid":true,"format":"${PortableDoc.Format}",""" +
                    s""""docSha256":"sha256-${PortableDoc.sha256Hex(bytes)}"}"""
                )
                System.out.flush()
                raider.core.result.ExitCodes.Success

  private def parse(
      argv: List[String]
  ): Either[RaiderError, java.nio.file.Path] =
    argv match
      case "--doc" :: value :: Nil if value.trim.nonEmpty =>
        Right(Paths.get(value))
      case Nil =>
        Left(RaiderError.Configuration("check requires --doc <file>"))
      case "--doc" :: _ =>
        Left(RaiderError.Configuration("--doc requires a value"))
      case unknown :: _ =>
        Left(
          RaiderError.Configuration(
            s"unknown argument '$unknown' (usage: raider workflow check --doc <file>)"
          )
        )

end PortableCheck
