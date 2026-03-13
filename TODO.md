# TODO

### Inline virtual calls with known exact type — partially done

When the receiver type is known precisely, virtual calls can be inlined even if
the method is overridden in subclasses. For example, in `new Foo().bar()` the
receiver is exactly `Foo`, so `bar` can be resolved and inlined without requiring
it to be effectively final.

**Done:** `ExactTypeValue` in `TypeFlowAnalyzer` tracks values from `NEW` instructions
through stores, loads, and casts. `CallGraph.isStaticallyResolved` uses this to
devirtualize calls when the exact type is known.

**Remaining: demand-driven interprocedural type analysis.** When the inliner wants to
inline a call but `isStaticallyResolved` is false (e.g., interface dispatch), trace
backwards through the receiver's definition chain to determine its concrete type, crossing
method boundaries as needed.

Example: `val b = Seq.newBuilder[Int]; b += 10; b.result().map(_ + 1)`.
The inliner wants to inline `map` (higher-order call with literal lambda). The static type
of `b.result()` is `Seq`, so `map` can't be resolved. Analysis:

1. Goal: know the return type of `b.result()`. Look up that method — it's the abstract
   `Builder.result`. We need to know which override, so determine the precise type of `b`.
2. `b` is the result of `Seq.newBuilder[Int]`. The receiver `Seq` is `object Seq`, which
   extends `SeqFactory.Delegate(List)`. So `Seq.newBuilder` resolves to `Delegate.newBuilder`.
3. Look inside `Delegate.newBuilder`: it calls `delegate.newBuilder`. The `delegate` field
   is set in `Delegate`'s constructor, and the receiver is `object Seq` (known singleton),
   so `delegate` is `object List`.
4. `List.newBuilder` returns `new ListBuffer()`. So `b` has type `ListBuffer`.
5. Look up `ListBuffer.result()`: returns `List`. So `b.result()` has type `List`.
6. `List` is sealed with two final subclasses (`::` and `Nil`), and neither overrides `map`.
   So `List.map` is statically resolved and can be inlined.

This is a form of **demand-driven interprocedural type analysis**. Related work:
- Sundaresan et al., "Practical Virtual Method Call Resolution for Java" (OOPSLA 2000) —
  Variable Type Analysis (VTA), propagation-based type tracking through assignments,
  parameters, and returns.
- Sridharan & Bodík, "Refinement-Based Context-Sensitive Points-To Analysis for Java"
  (PLDI 2006) — demand-driven backward tracing from a query site, using CFL-reachability.
- Scala.js linker optimizer — does exactly this kind of type tracking on its tree-based IR.

Key design decisions for goron:
- **Demand-driven**: triggered only when the inliner has a callsite it wants to resolve,
  not a whole-program analysis. Limits scope and cost.
- **Interprocedural**: uses `byteCodeRepository.methodNode` to look inside callee bodies
  (all classfiles are available).
- **Singleton field tracking**: for Scala objects (`MODULE$`), track constructor parameter →
  field assignment to determine field values.
- **Depth-limited**: set a bound on interprocedural tracing depth (e.g., 5 levels).
- **Cached**: memoize per (class, method, desc) → return type to avoid redundant analysis.
- **Works on bytecode**: use `ProdConsAnalyzer` for backward tracing through the stack.

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

### Create an sbt plugin

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

**2. filter/sum not inlined — interface dispatch blocks devirtualization**
(`issue: filter not inlined...`)
`map` returns static type `IndexedSeq` (an interface). Calls to `filter` and `sum` on this
result are `INVOKEINTERFACE`, which fails `isStaticallyResolved` in the call graph. Even
closed-world analysis can't help because `IndexedSeq` has multiple implementations.
Requires the demand-driven interprocedural type analysis described in "Inline virtual calls
with known exact type" above to trace through `IndexedSeq$.newBuilder().result()` → `Vector`.
Note: `sum` faces the same interface dispatch barrier as `filter`.

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
