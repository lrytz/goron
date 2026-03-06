/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron.optimizer

import goron.GoronConfig

/** Replacement for PostProcessorFrontendAccess.CompilerSettings. Translates GoronConfig into the settings interface
  * expected by the forked optimizer.
  */
trait CompilerSettings {
  def debug: Boolean

  def target: String

  def optAddToBytecodeRepository: Boolean
  def optBuildCallGraph: Boolean
  def optUseAnalyzerCache: Boolean

  def optNone: Boolean

  def optUnreachableCode: Boolean
  def optNullnessTracking: Boolean
  def optBoxUnbox: Boolean
  def optCopyPropagation: Boolean
  def optRedundantCasts: Boolean
  def optSimplifyJumps: Boolean
  def optCompactLocals: Boolean
  def optClosureInvocations: Boolean
  def optAllowSkipCoreModuleInit: Boolean
  def optAssumeModulesNonNull: Boolean
  def optAllowSkipClassLoading: Boolean

  def optInlinerEnabled: Boolean
  def optInlineFrom: List[String]
  def optInlineHeuristics: String

  def optWarningNoInlineMixed: Boolean
  def optWarningNoInlineMissingBytecode: Boolean
  def optWarningNoInlineMissingScalaInlineInfoAttr: Boolean
  def optWarningEmitAtInlineFailed: Boolean
  def optWarningEmitAnyInlineFailed: Boolean

  def optLogInline: Option[String]
  def optTrace: Option[String]
}

object CompilerSettings {
  def fromConfig(config: GoronConfig): CompilerSettings = new CompilerSettings {
    val debug = false
    val target = "17"

    val optAddToBytecodeRepository = config.optInlinerEnabled || config.optClosureInvocations
    val optBuildCallGraph = config.optInlinerEnabled || config.optClosureInvocations
    val optUseAnalyzerCache = true

    val optNone = !config.optLocalOptimizations && !config.optInlinerEnabled

    val optUnreachableCode = config.optUnreachableCode
    val optNullnessTracking = config.optNullnessTracking
    val optBoxUnbox = config.optBoxUnbox
    val optCopyPropagation = config.optCopyPropagation
    val optRedundantCasts = true
    val optSimplifyJumps = true
    val optCompactLocals = true
    val optClosureInvocations = config.optClosureInvocations
    val optAllowSkipCoreModuleInit = true
    val optAssumeModulesNonNull = true
    val optAllowSkipClassLoading = true

    val optInlinerEnabled = config.optInlinerEnabled
    val optInlineFrom = config.optInlineFrom
    val optInlineHeuristics = config.optInlineHeuristics

    val optWarningNoInlineMixed = false
    val optWarningNoInlineMissingBytecode = false
    val optWarningNoInlineMissingScalaInlineInfoAttr = false
    val optWarningEmitAtInlineFailed = config.optWarningEmitAtInlineFailed
    val optWarningEmitAnyInlineFailed = false

    val optLogInline: Option[String] = None
    val optTrace: Option[String] = None
  }
}
