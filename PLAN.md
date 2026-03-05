# Goron: Link-Time Optimizer for JVM Bytecode

## Context

The Scala 2.13 compiler has a bytecode optimizer that runs at compile time. It's limited: it only optimizes code being compiled, assumes an open world, and cannot eliminate dead code across the classpath.

Goron is a standalone link-time optimizer that takes the complete classpath (all jars) as input and produces a single optimized jar. With closed-world assumptions, it can inline more aggressively, devirtualize calls, and eliminate unused classes/methods. It works on any JVM bytecode — Scala 2.13, Scala 3, or plain Java — though Scala code benefits most due to the `ScalaInlineInfo` classfile attribute.

We fork the ~30 optimizer files from the Scala 2.13 compiler, re-package them under `goron.optimizer`, and replace all compiler-frontend abstractions with goron's own lightweight types.

## Project Structure

```
goron/
  build.sbt
  project/build.properties, plugins.sbt
  scala/                              # cloned scala/scala repo (gitignored)
  src/main/scala/
    goron/
      Goron.scala                     # Library API
      GoronCli.scala                  # CLI entry point
      GoronConfig.scala               # Configuration / settings
      JarIO.scala                     # Read/write jars
      Classpath.scala                 # Classpath abstraction (replaces BackendClassPath + AbstractFile)
      ReachabilityAnalysis.scala      # Whole-program dead code analysis (Phase 5)
    goron/optimizer/                   # Forked from scala.tools.nsc.backend.jvm
      BTypes.scala                    # Type hierarchy (ClassBType, ArrayBType, etc.)
      BTypesFromClassfile.scala       # Creates BTypes from parsed classfiles
      CoreBTypes.scala                # Well-known JVM types (Object, String, boxed, etc.)
      PostProcessor.scala             # Orchestrates all optimizations
      BackendReporting.scala          # Warning/error types + reporter trait
      PerRunInit.scala                # Cache clearing infrastructure
      AsmUtils.scala                  # ASM utilities
      opt/                            # 11 files
        ByteCodeRepository.scala      # Loads/caches ClassNodes
        BytecodeUtils.scala           # Bytecode analysis utilities
        CallGraph.scala               # Call graph representation
        Inliner.scala                 # Inlining engine
        InlinerHeuristics.scala       # Inlining decision logic
        ClosureOptimizer.scala        # Closure invocation rewriting
        LocalOpt.scala                # Per-method optimization fixpoint loop
        BoxUnbox.scala                # Box/unbox elimination
        CopyProp.scala                # Copy propagation
        FifoCache.scala               # LRU cache
        InlineInfoAttribute.scala     # ScalaInlineInfo classfile attribute
      analysis/                       # 8 files
        BackendUtils.scala            # Analysis utilities
        AsmAnalyzer.scala, AliasingAnalyzer.scala, NullnessAnalyzer.scala
        ProdConsAnalyzer.scala, TypeFlowAnalyzer.scala
        InstructionStackEffect.scala, package.scala
  src/test/scala/goron/
```

## Dependencies

```scala
// build.sbt
scalaVersion := "2.13.16"
libraryDependencies ++= Seq(
  "org.scala-lang.modules" % "scala-asm" % "9.7.1-scala-1",
  "com.lihaoyi" %% "utest" % "0.8.4" % Test,
)
testFrameworks += new TestFramework("utest.runner.Framework")
// sbt-assembly plugin for fat jar
```

**No scala-compiler dependency.** We only depend on `scala-asm` (Scala's fork of ASM, package `scala.tools.asm`).

## Compiler Dependencies to Replace

The forked files import several types from the compiler. We replace them:

| Compiler Type | Used In | Replacement |
|---|---|---|
| `scala.reflect.internal.util.Position` | BackendReporting, PostProcessor, CallGraph, BackendUtils | Our own `goron.optimizer.Position` (simple case class or just use string messages) |
| `scala.reflect.internal.util.NoPosition` | PostProcessor, ByteCodeRepository, ClosureOptimizer | Singleton `Position.NoPosition` |
| `scala.reflect.internal.util.Statistics` | BackendStats | Remove entirely — no stats tracking needed initially |
| `scala.reflect.internal.util.Collections._` | BytecodeUtils (`foreachWithIndex`) | Inline the utility (trivial) |
| `scala.reflect.io.AbstractFile` | PostProcessorFrontendAccess (BackendClassPath) | Replace with `goron.Classpath` trait: `findClassBytes(internalName): Option[Array[Byte]]` |
| `scala.reflect.internal.util.JavaClearable` | PostProcessorFrontendAccess | Inline or remove (simple cache clearing) |
| `GenBCode._` constants | BytecodeUtils, BackendUtils | Define locally: `PublicStatic`, `PublicStaticFinal`, `PrivateStaticFinal`, `CLASS_CONSTRUCTOR_NAME`, `INSTANCE_CONSTRUCTOR_NAME` |
| `PostProcessorFrontendAccess` | BTypes, PostProcessor, all opt/ files | **Remove entirely.** Inline its responsibilities: config passed directly, classpath passed directly, reporting passed directly |
| `CoreBTypesFromSymbols` | CoreBTypes.scala | **Remove.** Keep abstract `CoreBTypes` trait, implement `CoreBTypesFromClassfile` that initializes all types by parsing well-known classfiles |

## Architecture (replacing PostProcessorFrontendAccess)

The compiler uses `PostProcessorFrontendAccess` to abstract over compiler-frontend data. We don't have a compiler frontend — we only have bytecode. So we remove this abstraction entirely and pass dependencies directly.

**`PostProcessor`** currently accesses `frontendAccess` for:
1. **`compilerSettings`** → replace with `GoronConfig` passed directly
2. **`backendReporting`** → replace with a `Reporter` trait defined in `BackendReporting.scala`
3. **`backendClassPath`** → replace with `Classpath` trait (returns `Option[Array[Byte]]`)
4. **`recordPerRunCache`** → simplify: single run, manage caches directly
5. **`unsafeStatistics`** → remove
6. **`getEntryPoints`** → not needed in PostProcessor (goron handles this externally)
7. **`javaDefinedClasses`** → not needed (compiler-only concept)
8. **`frontendSynch`** → remove (no compiler frontend to synchronize with)

**`BTypes`** currently has `val frontendAccess` for cache registration and `frontendSynch`. Replace with direct config/classpath references.

## Files to Fork — Detailed Changes

### Core files (re-package to `goron.optimizer`)

| File | Changes |
|------|---------|
| **`BTypes.scala`** | Remove `frontendAccess` dependency. Pass classpath and config directly. Replace `recordPerRunJavaMapCache` with direct map creation. Remove `frontendSynch` (no concurrent compiler). |
| **`BTypesFromClassfile.scala`** | Minimal changes — already works on classfiles. Fix package refs. |
| **`CoreBTypes.scala`** | Remove `CoreBTypesFromSymbols`. Add `CoreBTypesFromClassfile` that initializes types from known internal names. |
| **`PostProcessor.scala`** | Major refactor: remove `frontendAccess`, take `config: GoronConfig`, `classpath: Classpath`, `reporter: Reporter` directly. Remove `classfileWriters`, `sendToDisk`, `GeneratedClass`, `GeneratedCompilationUnit`. Keep `runGlobalOptimizations`, `localOptimizations`, `serializeClass`. Add `initialize()` without Global. |
| **`BackendReporting.scala`** | Replace `Position` with our own. Keep warning/error type hierarchy (OptimizerWarning etc.). Define `Reporter` trait here. |
| **`BackendStats.scala`** | Remove `Statistics` dependency. Stub out or remove. |
| **`PerRunInit.scala`** | Keep as-is (pure utility). |
| **`AsmUtils.scala`** | Minimal changes. |

### opt/ files (re-package to `goron.optimizer.opt`)

| File | Changes |
|------|---------|
| **`ByteCodeRepository.scala`** | Replace `BackendClassPath.findClassFile` with `Classpath.findClassBytes`. Remove `AbstractFile` usage. Increase/remove cache limit. Replace `NoPosition` with our own. |
| **`BytecodeUtils.scala`** | Replace `GenBCode._` with local constants. Replace `Collections.foreachWithIndex` with inline. |
| **`CallGraph.scala`** | Replace `Position`/`NoPosition` with our own. Fix package refs. |
| **`Inliner.scala`** | Fix package refs. |
| **`InlinerHeuristics.scala`** | Fix package refs. |
| **`ClosureOptimizer.scala`** | Replace `NoPosition`. Fix package refs. |
| **`LocalOpt.scala`** | Fix package refs. |
| **`BoxUnbox.scala`** | Fix package refs. |
| **`CopyProp.scala`** | Fix package refs. |
| **`FifoCache.scala`** | No changes. |
| **`InlineInfoAttribute.scala`** | No changes. |

### analysis/ files (re-package to `goron.optimizer.analysis`)

| File | Changes |
|------|---------|
| **`BackendUtils.scala`** | Replace `GenBCode._` constants, `Position`, fix package refs. |
| Others (7 files) | Fix package refs only. |

## Implementation Phases

We take a break after each phase to inspect and discuss.

### Phase 1: Skeleton + Passthrough ✅
Read jars in, write jar out, no optimization. Proves the I/O pipeline.
1. `build.sbt` with `scala-asm` dependency + sbt-assembly
2. `GoronConfig` — configuration case class
3. `Classpath` — trait + implementation that reads from jar entries
4. `JarIO` — read all `.class` entries from jars, write output jar
5. `GoronCli` — CLI argument parsing (main class)
6. `Goron` — library entry point: read → write passthrough
7. Test: output jar runs identically to input

### Phase 2: Fork Optimizer Files + Compile
All forked files compile under `goron.optimizer`.
1. Copy ~30 files, re-package (change `package` declarations and cross-imports)
2. Define `goron.optimizer.Position` (simple replacement)
3. Define local `GenBCode` constants in `BytecodeUtils`
4. Replace `PostProcessorFrontendAccess` references — pass config/classpath/reporter directly
5. Remove `CoreBTypesFromSymbols`, `ClassfileWriters` dependency, `Statistics` dependency
6. Stub out what's needed to compile
7. Iterate until `sbt compile` succeeds

### Phase 3: Wire Up + Local Optimizations
Per-method optimizations work end-to-end.
1. Implement `CoreBTypesFromClassfile` — initialize well-known types from their internal names
2. Wire `PostProcessor` with goron's config, classpath, reporter
3. Pipeline: read jars → parse ClassNodes → `localOptimizations` per class → serialize → write jar
4. Test correctness on a real Scala project (compile something, run through goron, run output)

### Phase 4: Global Optimizations (Inlining)
Cross-class/cross-jar inlining works.
1. Add all classes to `ByteCodeRepository`
2. Build call graph via `callGraph.addClass`
3. Run `inliner.runInlinerAndClosureOptimizer()`
4. Configure `optInlineFrom = List("**")` (inline from everything)
5. Test with cross-jar method calls, verify correctness

### Phase 5: Dead Code Elimination
Remove unreachable classes and methods.
1. Implement `ReachabilityAnalysis` — BFS from entry points
2. After optimization, filter output to reachable classes only
3. Test: jar size reduction + correctness
4. Handle edge cases: reflection hints, `META-INF/services`

### Phase 6: Closed-World Enhancements
Exploit full classpath knowledge.
1. Build whole-program class hierarchy from all loaded classes
2. Mark classes/methods as effectively final when no subclass overrides
3. Feed into `InlineInfo` for more aggressive inlining
4. Devirtualize: `invokevirtual` → `invokestatic` for monomorphic call sites

## Verification (per phase)

1. `sbt compile` — goron compiles
2. Prepare a test Scala project (simple app with a main class)
3. Run goron: `sbt "run --input test-app.jar --output out.jar --entry com.example.Main"`
4. Run output: `java -jar out.jar` — verify identical behavior
5. `sbt test` — run goron's utest suite

## Key Risks

- **Hidden compiler dependencies** — Phase 2 is dedicated to finding and fixing these. The `package scala.tools.nsc` / `package backend.jvm` two-line package declaration is a source of implicit imports.
- **CoreBTypes initialization** — many well-known types needed. We hardcode their internal names; these are stable across JDK versions.
- **BTypes cake pattern wiring** — the `postProcessor.type` path-dependent types require careful wiring. We keep the same cake pattern but inject our own dependencies.
- **ByteCodeRepository for link-time** — remove LRU eviction or increase cache dramatically, since we need all classes in memory.
