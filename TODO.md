# TODO

- [x] Eliminate unused methods from retained classes (currently only whole-class DCE)
- [ ] Improve CLI output: show progress and stats during optimization (classes parsed, reachable, inlined, eliminated, etc.)
- [ ] Create an sbt plugin
- [ ] Parallelize local optimizations and serialization (per-class, no shared mutable state)
- [ ] Remove path-dependent types / cake pattern from optimizer fork (move ClassBType, Callsite, etc. to top-level types; replace abstract vals + singleton type refinements with constructor parameters)
- [ ] Clean up compiler leftovers in optimizer fork:
  - Remove `ClassBTypeFromSymbol` and `fromSymbol` flag (always false, never read)
  - Remove `frontendSynch` (no-op wrapper, inline all call sites)
  - Remove `ClassNotFoundWhenBuildingInlineInfoFromSymbol` warning (can never be produced)
  - Simplify `isCompilingPrimitive` (always false)
  - Replace `LazyVar` / `perRunLazy` / `PerRunInit` with plain `lazy val`s (goron runs once per JVM, no multi-run reset needed)
  - Remove `ClearableJConcurrentHashMap` (unused)
  - Clean up stale comments referencing GenBCode, PostProcessorFrontendAccess, compiler symbols
- [x] Set up scalafmt
- [x] Set up sbt-header for license headers
- [ ] Better handling of Java module system accessibility restrictions (currently `optInlineFrom` excludes JDK internal packages as a crude workaround; need proper module-aware visibility checks for Java dependencies)
- [ ] Make closed-world analysis update InlineInfo so the inliner can exploit effectively-final methods on Scala classes (currently only sets ACC_FINAL, which the inliner ignores in favor of ScalaInlineInfo's effectivelyFinal)
- [ ] Build a reachability graph during analysis to support "why is X retained?" queries (record edges: which method/class caused each method/class to be enqueued, track virtual call resolution chains)
