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

### Inline virtual calls with known exact type

When the receiver type is known precisely, virtual calls can be inlined even if
the method is overridden in subclasses. For example, in `new Foo().bar()` the
receiver is exactly `Foo`, so `bar` can be resolved and inlined without requiring
it to be effectively final. This applies after constructor calls, after
`checkcast`, and potentially after type flow analysis determines a local always
holds a specific type.

The compiler's `CallGraph` already has a TODO for this (`"type analysis can
render more calls statically resolved"`). Currently `isStaticallyResolved` only
checks if the method or the receiver's declared class is effectively final, not
the actual type at the callsite.

### Generalized stack allocation / scalar replacement

The existing `BoxUnbox` pass eliminates allocations of primitive boxes, `Ref` classes, and
`Tuple1`–`Tuple22` by replacing them with local variables. It uses producer-consumer analysis
as escape analysis: if no consumer of a `NEW` escapes, field accesses become local loads/stores.

This could be generalized to eliminate allocations of **any** immutable class whose instance
doesn't escape, without hardcoding types. Concrete candidates:

- **Option/Some**: After inlining `getOrElse`, `map`, `fold`, etc., the pattern
  `new Some(x)` → `_.get` is a classic box-unbox. Consumers: `.get`, `._1`,
  `.isEmpty` (constant `false`). Immutable, single field — fits the existing M1/M2 framework.
- **Right/Left**: Similar single-field immutable wrappers.
- **Range**: After inlining `foreach` + closure elimination, what remains is field accesses
  (`start`, `end`, `step`) and methods like `isEmpty`. Three immutable fields — if all methods
  are inlined, the allocation can be replaced with three locals. Alternatively, a semantic
  rewrite (pattern-match the `Range.foreach` loop and lower to a while loop) may be more
  robust than relying on enough inlining to expose all field accesses.
- **Value classes**: Single-field wrappers with predictable constructor/accessor patterns.
  There's an existing `// TODO: add more` + commented-out `ValueClass` in BoxUnbox.

**Two possible approaches:**
1. *Extend BoxUnbox* with more `BoxKind` entries for specific types (Some, Range, etc.).
   Incremental, fits existing architecture, but remains a hardcoded list.
2. *General escape-analysis pass* that works on any `NEW` + `<init>` where the object
   doesn't escape. After inlining turns method calls into field accesses, replace
   GETFIELD/PUTFIELD with local variables. Subsumes all individual BoxKind entries.
   Harder on stack-based bytecode than on AST-level IR (cf. Scala.js `InlineClassInstanceReplacement`
   which does this on AST IR with an explicit "inlineable class" optimizer hint set by the
   compiler plugin — but only for Tuples and ArrayOps in practice).

### Dead field elimination

After closed-world analysis, inlining, and DCE, some fields may become write-only (assigned
in constructors or methods, but never read). Eliminating the field and the write instructions
would shrink objects and reduce initialization cost. Requires a whole-program analysis: scan
all retained methods for GETFIELD/GETSTATIC instructions, then strip fields (and their
writes) that have no readers.

### ~~Add JMH benchmark subproject~~ (done)

`bench/` subproject with sbt-jmh. `ScalacBenchmark` compares stock vs goron-optimized
scala-compiler 2.13.18. Run with `sbt "bench/Jmh/run ScalacBenchmark"`.
