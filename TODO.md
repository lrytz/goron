# TODO

### Inline virtual calls with known exact type — partially done

When the receiver type is known precisely, virtual calls can be inlined even if
the method is overridden in subclasses. For example, in `new Foo().bar()` the
receiver is exactly `Foo`, so `bar` can be resolved and inlined without requiring
it to be effectively final.

**Done:** `ExactTypeValue` in `TypeFlowAnalyzer` tracks values from `NEW` instructions
through stores, loads, and casts. `CallGraph.isStaticallyResolved` uses this to
devirtualize calls when the exact type is known.

**Done:** demand-driven interprocedural type analysis (`InterproceduralTypeAnalyzer`).
Traces return types through method bodies, tracks singleton field values through constructor
chains, and uses `ClassHierarchy.hasOverrideInSubclasses` for narrowed-type devirtualization.
Three-level type lattice: `ExactTypeValue` (JVM-verifiable), `InterproceduralExactTypeValue`
(exact but needs CHECKCAST), `NarrowedTypeValue` (upper bound, resolved if no override).
Handles the full `Seq.newBuilder.result().map` chain and the `(1 to 10).map.filter` pattern.

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

### Java enum classes broken after optimization

Goron's optimization breaks Java enum classes. `EnumSet.noneOf(cls)` throws
`ClassCastException: class xsbti.UseScope not an enum` when `cls` was loaded from a
goron-optimized jar. Likely goron strips or modifies enum metadata (e.g., the `ACC_ENUM`
access flag, the `values()` / `valueOf()` synthetic methods, or the superclass
`java.lang.Enum`). Discovered via `Scala3Bench` where the Scala 3 compiler uses
`EnumSet.of(UseScope.Default)` during initialization. Current workaround: add enum classes
as entry points so they're retained as-is.

### `effectivelyFinal` in `BTypesFromClassfile` should check class finality

`BTypesFromClassfile.classBTypeFromParsedClassfile` sets `effectivelyFinal` on
`MethodInlineInfo` using only `BytecodeUtils.isFinalMethod(methodNode)`. This misses
that all methods in a `final` class are effectively final. Checking
`BytecodeUtils.isFinalClass(classNode) || isFinalMethod(methodNode)` would mark more
methods as statically resolvable, enabling more inlining without interprocedural analysis.
See `BTypesFromClassfile.scala:182`.

### Improve error handling in LUB computation and external class loading

Silent catch-all handlers mask real errors:

- `PostProcessor.ClassWriterWithBTypeLub.getCommonSuperClass` catches `Throwable`
  (including `OutOfMemoryError`) and falls back to `java/lang/Object`. An incorrect LUB
  produces wrong stack frames → runtime `VerifyError`s that are extremely hard to debug.
  Narrow to expected exceptions and log when fallback is used.

- `ReachabilityAnalysis.collectExternalClassMethods` catches `Exception` and returns
  `Set.empty`, silently dropping all methods from a class hierarchy branch. At minimum log
  a warning so missing external classes are visible.

### Parallelize local optimizations and serialization

Per-class local optimizations and serialization have no shared mutable state and can run
in parallel (e.g., via `parallel collections` or a thread pool).

### Create an sbt plugin — DONE (initial version)

`sbt-plugin/` contains `sbt-goron`, an sbt plugin that extends sbt-assembly. The
`goronAssembly` task runs `assembly` first, then optimizes the fat jar with goron in a
forked JVM. See `sbt-plugin/src/main/scala/goron/sbt/GoronPlugin.scala`.

### Better Java module system handling

Currently `optInlineFrom` excludes JDK internal packages as a crude workaround. Need proper
module-aware visibility checks for Java dependencies.

### Build a reachability graph for "why is X retained?" queries

Record edges during reachability analysis: which method/class caused each method/class to
be enqueued, track virtual call resolution chains. Enables debugging why specific classes
survive DCE.

### Deep dive into CollectionPipeline benchmarks

`foldLeft` shows -45% and `mapFilterSum` shows -12%. The `mapFilterSum` pipeline
`(1 to 1000).map(_ * 2).filter(_ > 50).sum` was investigated in detail (see test cases in
`IntegrationTest.scala`). Current state after goron optimization:

- **map's internal loop is inlined**: the `Range.foreach` + `Builder.addOne` loop is inlined
  into the caller, and the map closure body (`_ * 2`) is inlined at the call site.
- **filter and sum are NOT inlined**: they remain as `INVOKEINTERFACE` calls on `IndexedSeq`.
- **Intermediate collections and boxing remain**.

Three independent improvement opportunities, each with a test case in `IntegrationTest.scala`:

**1. Map closure allocated but unused after inlining** — FIXED.
The root cause was that `NullnessInterpreter` did not recognize `LambdaMetaFactory`
INVOKEDYNAMIC results as non-null. After the closure optimizer rewrites `closure.apply()` →
direct body call and inserts a null check on the closure reference, nullness analysis couldn't
prove it non-null, so the null check remained, keeping the closure alive. Fix: return
`NotNullValue` for `LambdaMetaFactoryCall` in `NullnessInterpreter.naryOperation`. This
enables the chain: null-check elimination → push-pop → stale store → closure removal.

**2. filter/sum not inlined — interface dispatch blocks devirtualization** — FIXED (filter).
Interprocedural type analysis traces `IndexedSeq$.newBuilder().result()` → `Vector`, and
`hasOverrideInSubclasses` confirms no subclass of `Vector` overrides `filter`. The call is
now statically resolved and inlined. `sum` faces the same barrier but goes through
`IterableOnceOps` — not yet resolved.

**3. Boxing in map loop** (`issue: boxing in map loop...`)
The inlined map loop iterates via `Iterator[Object]`, unboxes to `int` with
`BoxesRunTime.unboxToInt`, applies the function, then re-boxes with `Integer.valueOf` before
`Builder.addOne(Object)`. The specialized `JFunction1$mcII$sp` avoids boxing for the closure
call itself, but the generic iterator/builder pipeline still boxes. Fix: after enough inlining
exposes the builder internals, generalized scalar replacement (see above) could eliminate the
boxing, or a specialized rewrite for known collection types could bypass the generic pipeline.

### Scala 3 support

Check what needs to change for goron to fully support Scala 3 bytecode. Scala 3 classfiles
don't have the `ScalaInlineInfo` attribute that goron relies on for inline heuristics
(which methods are final, annotated `@inline`, closures, etc.). Without it, goron falls back
to reading access flags only, missing opportunities. Investigate whether Scala 3 has an
equivalent mechanism, or whether goron needs to derive inline info from the classfile structure
(e.g., detecting SAM closures, final methods, bridge methods) without the attribute.

Investigate what goron does in the Scala3CompilerBench. Performance seems exactly the same with
and without.

### Apply cleanup commits from scala/scala3#25165

Review https://github.com/scala/scala3/pull/25165 for cleanup commits that can be applied to
goron's copy of the Scala 2 backend code. For example, the removal of `RightBiasedEither`.

### Learn from GraalVM JIT optimizations

Study GraalVM's Graal compiler for optimization passes and analyses that could be added or
improved in goron. Graal operates on a sea-of-nodes IR and does many of the same optimizations
goron targets (inlining, escape analysis, devirtualization) but with more sophisticated
implementations. Look for research papers, especially:
- Duboscq et al., "An Intermediate Representation for Speculative Optimizations in a
  Dynamic Compiler" (2013) — Graal IR design
- Stadler et al., "Partial Escape Analysis and Scalar Replacement for Java" (2014) —
  relevant to generalized scalar replacement in goron
- Wimmer & Franz, "Linear Scan Register Allocation on SSA Form" (2010)
- Sewe et al., "Da Capo Con Spirito: Benchmarking JVM Implementations" (2011)

GraalVM JIT source code is on GitHub but GPL, so we're not allowed to use it.
https://github.com/oracle/graal/blob/master/compiler/src/jdk.graal.compiler/src/jdk/graal/compiler/core/phases/CEOptimization.java

### Benchmark impact of ignoring ScalaInlineInfo

Add a mode to goron where it ignores the `ScalaInlineInfo` classfile attribute entirely,
falling back to bytecode-derived info (access flags, etc.) for all classes — the same
path Scala 3 classfiles take today. Compare benchmark results with and without the
attribute to determine whether `ScalaInlineInfo` provides meaningful optimization benefit
and whether it should be added to Scala 3 classfiles.

`InlineInfo` carries more than just `effectivelyFinal`:
- **SAM type detection**: whether a class is a single-abstract-method type, used by the
  inliner to identify higher-order method calls with closure arguments
- **`@inline`/`@noinline` annotations**: method-level hints that influence inliner
  heuristics (priority and suppression)
- **`effectivelyFinal`**: the Scala compiler knows more than `ACC_FINAL` (sealed
  hierarchies, private methods, methods in objects) — but goron's closed-world analysis
  and `isFinalClass` check (see TODO above) may recover most of this independently

### Specialization

Scala 2 has `@specialized` annotation support in the compiler, generating type-specific
variants of generic classes/methods to avoid boxing. This is limited to a fixed set of
primitive types and produces a combinatorial explosion of generated classes. Goron could
potentially do specialization at link time more selectively — only generating specialized
variants for type arguments that are actually used in the program (demand-driven).

Scala 3 dropped general `@specialized` support but retains built-in specialization for
`Function` types (and possibly `Tuple`). A link-time specializer could fill this gap for
Scala 3 without requiring compiler changes, and could go further than the Scala 2 approach
by specializing arbitrary classes based on closed-world type usage analysis.

Key questions:
- Can we specialize after erasure, working on bytecode? The type info is gone, but
  call sites with `Integer.valueOf`/`unboxToInt` patterns reveal the original types.
- Is it more practical to specialize individual hot methods (monomorphization) rather
  than entire classes?
- How does this interact with the existing `BoxUnbox` pass — specialization eliminates
  boxing at the source, while BoxUnbox eliminates it after the fact.
