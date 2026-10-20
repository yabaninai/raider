package raider.cli.lineage

import zio.test.*

import java.nio.file.Files

object ToolchainPinSpec extends ZIOSpecDefault:

  def spec = suite("LIN8B toolchain pin producer")(
    test("writes a consumer-readable pin and omits unknown model identity") {
      val directory = Files.createTempDirectory("lin8b-pin")
      val written = ToolchainPin.write(directory, None)
      val pin = EnvPinning.readPinned(directory.resolve(ToolchainPin.FileName))
      val current = EnvPinning.currentEnv()
      assertTrue(
        written == Right(directory.resolve(ToolchainPin.FileName)),
        pin == Right(
          EnvPinning.PinnedEnv(
            current.scalaVersion,
            current.jdkVersion,
            current.raiderVersion,
            None
          )
        ),
        EnvPinning.compare(
          pin.toOption.get,
          current
        ) == EnvPinning.PinVerdict.Matching
      )
    },
    test("records an observed model identity") {
      val directory = Files.createTempDirectory("lin8b-pin-model")
      val written = ToolchainPin.write(directory, Some("fixture-model"))
      val pin = EnvPinning.readPinned(directory.resolve(ToolchainPin.FileName))
      assertTrue(
        written.isRight,
        pin.toOption.flatMap(_.model) == Some("fixture-model")
      )
    }
  )

end ToolchainPinSpec
