# TODO

## Done

- [x] Eliminate unused methods from retained classes (currently only whole-class DCE)
- [x] Improve CLI output: show progress and stats during optimization
- [x] Clean up compiler leftovers in optimizer fork (ClassBTypeFromSymbol, frontendSynch, LazyVar, etc.)
- [x] Set up scalafmt
- [x] Set up sbt-header for license headers


## Architecture

### ~~Replace cake pattern with plain constructor injection~~ (done)

Replaced abstract classes + anonymous subclass wiring with concrete generic classes:
`class CallGraph[PP <: PostProcessor](val postProcessor: PP)`. PostProcessor instantiates
with `this.type`: `new CallGraph[this.type](this)`. The type parameter carries the singleton
type through, so the compiler can prove all modules share the same `BTypes` instance without
`self.type` refinement types or abstract vals.

### ~~Unify class hierarchy into a shared data structure~~ (done)

Created `ClassHierarchy` (classByName + subclasses map) built once in `Goron.run()` and
passed to `ReachabilityAnalysis` and `ClosedWorldAnalysis`. Eliminated the duplicate
subclass map construction that was in `methodLevelBFS` and `buildHierarchy`. The shared
structure also provides a natural home for the supertype index needed to fix RTA performance.

### ~~Eliminate global mutable state in ReachabilityAnalysis~~ (done)

Moved `collectExternalClassMethodsCached` from module-level into `methodLevelBFS` scope,
passed as a parameter to `collectExternalClassMethods`. Cache is now created fresh per
analysis run, eliminating the latent stale-state bug.

### Connect closed-world analysis to the inliner

`ClosedWorldAnalysis` computes `effectivelyFinalMethods` for all classes but only applies
`ACC_FINAL` flags to leaf classes in `applyToClassNodes`. The inliner uses
`ScalaInlineInfo.effectivelyFinal` rather than `ACC_FINAL`, so closed-world knowledge about
non-leaf effectively-final methods is computed but wasted. Update `InlineInfo` directly so
the inliner can exploit effectively-final methods on non-leaf Scala classes.

### Make DCE incremental

DCE (`Goron.scala:112–126`) runs a second full `reachableClassesAndMethods` BFS after
inlining — re-parsing all method bodies, re-resolving all virtual calls, re-computing the
entire reachable set from scratch (270s on scala-compiler). Consider an incremental approach:
have the inliner export a change set (modified/added/removed methods), then re-analyze only
affected methods and their transitive dependents. Requires the reachability analysis to
support incremental updates, and the inliner to track what it changed.

### Improve error handling in LUB computation and external class loading

Silent catch-all handlers mask real errors:

- `PostProcessor.ClassWriterWithBTypeLub.getCommonSuperClass` catches `Throwable`
  (including `OutOfMemoryError`) and falls back to `java/lang/Object`. An incorrect LUB
  produces wrong stack frames → runtime `VerifyError`s that are extremely hard to debug.
  Narrow to expected exceptions and log when fallback is used.

- `ReachabilityAnalysis.collectExternalClassMethods` catches `Exception` and returns
  `Set.empty`, silently dropping all methods from a class hierarchy branch. At minimum log
  a warning so missing external classes are visible.


## Performance

### Improve reachability analysis performance (219s on scala-compiler)

The RTA virtual dispatch in `methodLevelBFS` has a quadratic hotspot:
- `enqueueVirtualCall` iterates **all** `instantiatedClasses` for each new virtual call
- `markInstantiated` iterates **all** `virtualCallTargets` for each new class
- Both call `isSubclassOf`, which is O(hierarchy depth) with no memoization

Total cost: O(V × I × D) where V = virtual call targets, I = instantiated classes,
D = hierarchy depth.

Fixes:
- Index instantiated classes by supertype for O(1) lookup instead of scanning all
- Precompute transitive subclass sets or use union-find for `isSubclassOf`
- Build a `(name, desc) → MethodNode` map per `ClassNode` instead of linear scan of
  `cn.methods` (Java List) — currently O(n) per lookup, called thousands of times
- Cache `externalMethods` / `collectExternalClassMethods` results across calls
  (already partially cached, verify completeness)

### Parallelize local optimizations and serialization

Per-class local optimizations and serialization have no shared mutable state and can run
in parallel (e.g., via `parallel collections` or a thread pool).


## Features

### Create an sbt plugin

### Better Java module system handling

Currently `optInlineFrom` excludes JDK internal packages as a crude workaround. Need proper
module-aware visibility checks for Java dependencies.

### Build a reachability graph for "why is X retained?" queries

Record edges during reachability analysis: which method/class caused each method/class to
be enqueued, track virtual call resolution chains. Enables debugging why specific classes
survive DCE.
