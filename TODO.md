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

### ~~Connect closed-world analysis to the inliner~~ (done)

`applyToClassNodes` now updates the `ScalaInlineInfo` attribute on each ClassNode, marking
methods as `effectivelyFinal` based on closed-world analysis. This lets the inliner exploit
effectively-final methods on non-leaf Scala classes (previously only leaf-class methods
benefited via `ACC_FINAL`). On the scala-compiler benchmark, this enabled 1520 more methods
to be stripped (26890 → 28410).

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

### ~~Improve reachability analysis performance~~ (done)

Eliminated the quadratic RTA hotspot with three optimizations in `ClassHierarchy`:
- Precomputed `transitiveSupertypes` for O(1) `isSubclassOf` (was O(hierarchy depth))
- Indexed instantiated classes by supertype (`instantiatedBySuper`) and virtual calls by
  owner (`virtualCallsByOwner`) — both loops now O(relevant) instead of O(all)
- Per-class `methodIndex: Map[(name, desc) → MethodNode]` for O(1) method lookup
  (was O(n) linear scan of `cn.methods` Java List)

Also fixed a pre-existing bug: `enqueueVirtualCall` now also calls
`resolveAndEnqueueMethod(owner, name, desc)` to retain the method at the JVM's symbolic
resolution target (the declared owner or its supertypes), not just on concrete dispatch
targets. Without this, INVOKEINTERFACE/INVOKEVIRTUAL resolution would fail with
`NoSuchMethodError` when the method was defined on a trait/interface but stripped because
the RTA analysis only kept it on the concrete mixin forwarder class.

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

### ~~Add JMH benchmark subproject~~ (done)

`bench/` subproject with sbt-jmh. `ScalacBenchmark` compares stock vs goron-optimized
scala-compiler 2.13.18. Run with `sbt "bench/Jmh/run ScalacBenchmark"`.
