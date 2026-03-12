# TODO

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

`foldLeft` shows -45% and `mapFilterSum` shows -12%. Investigate what optimizations goron
actually applies: inspect the inline log, jardiff the stock vs optimized bytecode, and
understand the resulting code. Is it ideal, or are there further optimization opportunities
(e.g., eliminating remaining allocations, fusing collection operations, scalar-replacing
intermediate collections)?

### Scala 3 support

Check what needs to change for goron to fully support Scala 3 bytecode. Scala 3 classfiles
don't have the `ScalaInlineInfo` attribute that goron relies on for inline heuristics
(which methods are final, annotated `@inline`, closures, etc.). Without it, goron falls back
to reading access flags only, missing opportunities. Investigate whether Scala 3 has an
equivalent mechanism, or whether goron needs to derive inline info from the classfile structure
(e.g., detecting SAM closures, final methods, bridge methods) without the attribute.

Investigate what goron does in the Scala3CompilerBench. Performance seems exactly the same with
and without.
