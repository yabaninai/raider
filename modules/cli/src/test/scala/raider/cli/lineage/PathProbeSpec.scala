package raider.cli.lineage

import zio.test.*

object PathProbeSpec extends ZIOSpecDefault:

  def spec = suite("probe")(
    test("cl") {
      val cl = ClassLoader.getSystemClassLoader
      System.err.println("PROBE-CL:" + cl.getClass.getName)
      cl match
        case u: java.net.URLClassLoader =>
          val paths = u.getURLs.map(_.toString).toList
          System.err.println("PROBE-N:" + paths.length)
          paths.take(8).foreach(p => System.err.println("PROBE-U:" + p))
        case other =>
          System.err.println("PROBE-NOT-URLCL")
      List(
        ("cli", raider.cli.Main.getClass),
        ("core", raider.core.result.ExitCodes.getClass),
        ("dsl", raider.dsl.lineage.store.LineageDataStore.getClass),
        ("zio", zio.ZIO.succeed(1).getClass)
      ).foreach { (tag, c) =>
        System.err.println(
          s"PROBE-$tag:" + c.getProtectionDomain.getCodeSource.getLocation
        )
      }
      assertTrue(true)
    }
  )
