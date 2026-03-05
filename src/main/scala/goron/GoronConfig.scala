package goron

case class GoronConfig(
  inputJars: List[String],
  outputJar: String,
  entryPoints: List[String] = Nil,
  // Optimizer settings
  optInlineFrom: List[String] = List("**"),
  optInlineHeuristics: String = "at-inline-annotated-and-higher-order",
  maxInlineSize: Int = 35,
  maxIndyLambdaSize: Int = 40,
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
  verbose: Boolean = false,
)
