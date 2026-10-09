package billing

import scala.io.Source

/** Minimal launcher that reads app.conf and prints the retry budget. */
object Launcher:
  def main(args: Array[String]): Unit =
    val conf = loadConf("app.conf")
    val retries = conf.getOrElse("retry.max", "0").toInt
    val version = conf.getOrElse("app.version", "unknown")
    println(s"orders-billing $version starting with retry.max=$retries")

  def loadConf(path: String): Map[String, String] =
    Source.fromFile(path).getLines().toList
      .filter(l => l.trim.nonEmpty && !l.trim.startsWith("#"))
      .map(l => l.split("=", 2).map(_.trim))
      .map(a => a(0) -> a(1))
      .toMap
