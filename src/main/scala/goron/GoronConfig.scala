/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron

case class GoronConfig(
    inputJars: List[String],
    outputJar: String,
    entryPoints: List[String] = Nil,
    // Optimizer settings
    optInlineFrom: List[String] = List("**", "!jdk.**", "!java.**", "!sun.**"),
    optInlineHeuristics: String = "default",
    optWarningEmitAtInlineFailed: Boolean = false,
    optLocalOptimizations: Boolean = true,
    optInlinerEnabled: Boolean = true,
    optClosureInvocations: Boolean = true,
    optCopyPropagation: Boolean = true,
    optBoxUnbox: Boolean = true,
    optDeadCode: Boolean = true,
    optUnreachableCode: Boolean = true,
    optNullnessTracking: Boolean = true,
    // Link-time features
    eliminateDeadCode: Boolean = true,
    closedWorld: Boolean = true,
    verbose: Boolean = false
)
