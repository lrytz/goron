/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron

object GoronCli {
  def main(args: Array[String]): Unit = {
    val config = parseArgs(args.toList)
    config match {
      case Some(c) => Goron.run(c)
      case None => System.exit(1)
    }
  }

  def parseArgs(args: List[String]): Option[GoronConfig] = {
    var inputJars = List.empty[String]
    var outputJar = ""
    var entryPoints = List.empty[String]
    var verbose = false
    var optInlinerEnabled = true
    var optClosureInvocations = true
    var eliminateDeadCode = true

    var remaining = args
    while (remaining.nonEmpty) {
      remaining match {
        case "--input" :: jar :: rest =>
          inputJars = inputJars :+ jar
          remaining = rest
        case "--output" :: jar :: rest =>
          outputJar = jar
          remaining = rest
        case "--entry" :: entry :: rest =>
          entryPoints = entryPoints :+ entry
          remaining = rest
        case "--no-inline" :: rest =>
          optInlinerEnabled = false
          optClosureInvocations = false
          remaining = rest
        case "--no-dce" :: rest =>
          eliminateDeadCode = false
          remaining = rest
        case "--verbose" :: rest =>
          verbose = true
          remaining = rest
        case "--help" :: _ =>
          printUsage()
          return None
        case unknown :: _ =>
          System.err.println(s"Unknown option: $unknown")
          printUsage()
          return None
        case Nil =>
          remaining = Nil
      }
    }

    if (inputJars.isEmpty) {
      System.err.println("Error: at least one --input jar is required")
      printUsage()
      return None
    }
    if (outputJar.isEmpty) {
      System.err.println("Error: --output is required")
      printUsage()
      return None
    }

    Some(GoronConfig(
      inputJars = inputJars,
      outputJar = outputJar,
      entryPoints = entryPoints,
      verbose = verbose,
      optInlinerEnabled = optInlinerEnabled,
      optClosureInvocations = optClosureInvocations,
      eliminateDeadCode = eliminateDeadCode,
    ))
  }

  private def printUsage(): Unit = {
    System.err.println(
      """Usage: goron [options]
        |  --input <jar>    Input jar file (can be specified multiple times)
        |  --output <jar>   Output jar file
        |  --entry <class>  Entry point class (internal name, can be repeated)
        |  --no-inline      Disable inlining
        |  --no-dce         Disable dead code elimination
        |  --verbose        Verbose output
        |  --help           Show this help""".stripMargin)
  }
}
