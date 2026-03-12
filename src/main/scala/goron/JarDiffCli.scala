/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron

object JarDiffCli {
  def main(args: Array[String]): Unit = {
    var detail = false
    var filter: Option[String] = None
    var context = 3
    var decompile = false
    var positional = List.empty[String]

    var remaining = args.toList
    while (remaining.nonEmpty) {
      remaining match {
        case "--detail" :: rest =>
          detail = true
          remaining = rest
        case "--decompile" :: rest =>
          decompile = true
          detail = true
          remaining = rest
        case "--filter" :: pat :: rest =>
          filter = Some(pat)
          remaining = rest
        case "--context" :: n :: rest =>
          context = n.toInt
          remaining = rest
        case "--help" :: _ =>
          printUsage()
          return
        case arg :: rest if arg.startsWith("--") =>
          System.err.println(s"Unknown option: $arg")
          printUsage()
          System.exit(1)
          return
        case arg :: rest =>
          positional = positional :+ arg
          remaining = rest
        case Nil =>
          remaining = Nil
      }
    }

    if (positional.size != 2) {
      System.err.println("Error: expected exactly two jar files (before and after)")
      printUsage()
      System.exit(1)
      return
    }

    val beforeJar = positional(0)
    val afterJar = positional(1)

    val result = JarDiff.compare(beforeJar, afterJar, detail = detail, filter = filter, decompile = decompile)

    if (detail) {
      print(JarDiff.formatDetail(result))
    } else {
      print(JarDiff.formatSummary(result))
    }
  }

  private def printUsage(): Unit = {
    System.err.println("""Usage: jardiff [options] <before.jar> <after.jar>
        |  --detail           Show bytecode-level unified diff for changed methods
        |  --decompile        Use CFR decompiler for diffs (requires CFR on classpath)
        |  --filter <pattern> Only show classes matching pattern (substring match)
        |  --context <n>      Lines of context in diffs (default: 3)
        |  --help             Show this help""".stripMargin)
  }
}
