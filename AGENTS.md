# Goron: Link-Time Optimizer for JVM Bytecode

## What is Goron?

Goron is a standalone link-time optimizer that takes one or more JVM jars as input and produces a single optimized jar. It performs cross-jar inlining, closure optimization, dead code elimination, and closed-world optimizations. It works on any JVM bytecode but benefits most from Scala 2.13 code (which includes `ScalaInlineInfo` classfile attributes).

The optimizer is forked from the Scala 2.13 compiler backend (~30 files), re-packaged under `goron.optimizer`, with all compiler-frontend dependencies replaced by lightweight goron abstractions.

## Project Structure

```
src/main/scala/goron/
  Goron.scala                  # Main pipeline: read jars -> optimize -> write jar
  GoronCli.scala               # CLI entry point (--input, --output, --entry, etc.)
  GoronConfig.scala            # Configuration case class
  JarIO.scala                  # Jar read/write utilities
  Classpath.scala              # Classpath abstraction (JarClasspath, RuntimeClasspath)
  ReachabilityAnalysis.scala   # Two-phase dead code analysis (method-level BFS + load closure)
  ClosedWorldAnalysis.scala    # Marks effectively-final classes/methods

  optimizer/                   # Forked from scala.tools.nsc.backend.jvm
    BTypes.scala               # Type hierarchy (cake pattern root)
    BTypesFromClassfile.scala   # Creates BTypes from parsed classfiles
    CoreBTypes.scala           # Well-known JVM types (Object, String, boxed types)
    PostProcessor.scala        # Orchestrates all optimizations
    BackendReporting.scala     # Warning/error types + reporter trait
    CompilerSettings.scala     # Optimizer settings (wraps GoronConfig)
    AsmUtils.scala             # ASM utilities
    PerRunInit.scala           # Cache clearing infrastructure
    Position.scala             # Simple position type (replaces compiler Position)
    opt/
      ByteCodeRepository.scala # Loads/caches ClassNodes
      CallGraph.scala          # Call graph representation
      Inliner.scala            # Inlining engine
      InlinerHeuristics.scala  # Inlining decision logic
      ClosureOptimizer.scala   # Closure invocation rewriting (removes InvokeDynamic)
      LocalOpt.scala           # Per-method optimization fixpoint loop
      BoxUnbox.scala           # Box/unbox elimination
      CopyProp.scala           # Copy propagation
      BytecodeUtils.scala      # Bytecode analysis utilities
      InlineInfoAttribute.scala # ScalaInlineInfo classfile attribute
      FifoCache.scala          # LRU cache
    analysis/
      BackendUtils.scala       # Analysis utilities
      AsmAnalyzer.scala, AliasingAnalyzer.scala, NullnessAnalyzer.scala
      ProdConsAnalyzer.scala, TypeFlowAnalyzer.scala
      InstructionStackEffect.scala, package.scala

src/test/scala/goron/
  testkit/
    GoronTesting.scala         # Test infrastructure: embedded compiler, pipeline helpers
    ASMConverters.scala        # ASM bytecode -> readable instruction types
  InlinerTest.scala            # Inliner unit tests
  ClosureOptimizerTest.scala   # Closure optimization tests
  MethodLevelOptsTest.scala    # Local optimization tests
  BoxUnboxTest.scala           # Box/unbox elimination tests
  GoronTest.scala              # Basic pipeline tests
  IntegrationTest.scala        # Full pipeline tests (user code + scala-library)
```

## Build & Test

```bash
sbt compile          # Compile
sbt test             # Run all tests (munit)
sbt assembly         # Build fat jar
```

Dependencies: Scala 2.13.18, scala-asm 9.9.0-scala-1, munit 1.2.4 (test), scala-compiler (test only).

## CLI Usage

```bash
java -jar goron.jar \
  --input app.jar --input scala-library.jar \
  --output optimized.jar \
  --entry com/example/Main \
  --verbose
```

Flags: `--input` (repeatable), `--output`, `--entry` (repeatable, internal class names), `--no-inline`, `--no-closure-opt`, `--no-local-opt`, `--no-dce`, `--no-closed-world`, `--verbose`, `--help`.

Entry points are classes reachable by the JVM at startup (main classes, reflection targets, service providers). Classes not transitively reachable from entry points are eliminated.

## Pipeline (Goron.scala)

1. Read all input jars
2. Parse classfiles into ASM ClassNodes (with `InlineInfoAttributePrototype` for ScalaInlineInfo)
3. Reachability analysis (method-level BFS from entry points)
4. Add reachable classes to ByteCodeRepository as "compiling", unreachable as "parsed" (type resolution only)
5. Closed-world analysis: mark effectively-final classes/methods with ACC_FINAL
6. Global optimizations: inlining + closure optimization
7. Local optimizations: copy propagation, box/unbox elimination, dead code removal per method
8. Second DCE pass (inlining may have made more classes unreachable)
9. Serialize and write output jar

## Key Architecture Decisions

**Cake pattern**: The optimizer uses Scala's cake pattern with path-dependent types. `BTypes` is the root trait; `PostProcessor`, `CallGraph`, `Inliner` etc. all depend on a specific `BTypes` instance. Wiring is done in `Goron.createPostProcessor` and `GoronTesting.createPostProcessor`.

**Two-phase reachability analysis** (`ReachabilityAnalysis.scala`):
- Phase 1 (method-level BFS): follows only methods that are actually called, resolving virtual dispatch and inherited methods through the class hierarchy.
- Phase 2 (load closure): ensures all classes referenced in method bodies of retained classes exist, since the JVM verifier may resolve constant pool entries eagerly.

**ScalaInlineInfo attribute**: Scala-compiled classes carry a custom `ScalaInlineInfo` attribute with inline hints and `effectivelyFinal` flags. Must pass `InlineInfoAttributePrototype` to `ClassReader.accept()` or the attribute is silently dropped. The inliner uses `InlineInfo.effectivelyFinal` (from this attribute) rather than ACC_FINAL for dispatch resolution.

**Closed-world analysis**: Builds class hierarchy across ALL classes, but only applies ACC_FINAL markers to reachable classes. For Scala classes, the ScalaInlineInfo attribute's effectivelyFinal takes precedence over ACC_FINAL for inliner decisions.

## Testing

Tests use munit. The `GoronTesting` trait provides:
- An embedded Scala compiler (`scalac`) for compiling source strings to bytecode
- `compileAndOptimize(code)` — compile + run through optimizer pipeline
- `compileAndRunFullPipeline(code, entryPoints)` — full pipeline with scala-library (for integration tests)
- Assertion helpers: `assertDoesNotInvoke`, `assertNoIndy`, `assertSameCode`, etc.
- ASM ClassNode/MethodNode access helpers

`GoronTesting.scalaLibraryNodes` is a lazy val that parses scala-library once per JVM for integration test performance.

## Known Limitations

- **Reflection**: Classes loaded via reflection (e.g., `Class.forName`, `ScalaClassLoader.create`) are invisible to static analysis. They must be specified as `--entry` points.
- **No method-level elimination**: Goron eliminates unused classes but does not strip unused methods from retained classes.
- **JDK classes**: JDK internal classes are resolved at runtime via classloader fallback, not included in the optimized jar.
