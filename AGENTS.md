# Goron: Link-Time Optimizer for JVM Bytecode

## Agents Behavior

- Think before acting. Read existing files before writing code.
- Be concise in responses but thorough in reasoning.
- No sycophantic openers or closing fluff.
- No em dashes, smart quotes, or Unicode characters. ASCII only.
- Keep solutions simple and direct. No over-engineering.
- If unsure: say so. Never guess or invent file paths and function names.
- User instructions take precedence over this file.

## What is Goron?

Goron is a standalone link-time optimizer that takes one or more JVM jars as input and produces a single optimized jar. It performs cross-jar inlining, closure optimization, dead code elimination, and closed-world optimizations. It works on any JVM bytecode but benefits most from Scala 2.13 code (which includes `ScalaInlineInfo` classfile attributes).

The optimizer is forked from the Scala 2.13 compiler backend (~30 files), re-packaged under `goron.optimizer`, with all compiler-frontend dependencies replaced by lightweight goron abstractions.

## Project Structure

- `src/main/scala/goron/` — Goron's own code: pipeline (`Goron.scala`), CLI (`GoronCli.scala`), config, jar I/O, classpath, reachability analysis, closed-world analysis.
- `src/main/scala/goron/optimizer/` — Forked from `scala.tools.nsc.backend.jvm` (~30 files). Inliner, closure optimizer, local optimizations, call graph, type hierarchy (cake pattern). Mostly unchanged from upstream except replacing compiler-frontend dependencies.
- `src/test/scala/goron/` — munit tests. `testkit/GoronTesting.scala` provides an embedded compiler and pipeline helpers. `IntegrationTest.scala` runs the full pipeline with scala-library.

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

**Path-dependent types**: `BTypes` has inner types (`ClassBType`, etc.), so the compiler must prove all modules share the same `BTypes` instance. Each module is a generic class parameterized on the `PostProcessor` singleton type: `class CallGraph[PP <: PostProcessor](val postProcessor: PP)`. PostProcessor instantiates with `this.type`: `new CallGraph[this.type](this)`. This carries the singleton type through, unifying path-dependent types across modules without abstract classes or refinement types.

**Two-phase reachability analysis** (`ReachabilityAnalysis.scala`):
- Phase 1 (method-level BFS): follows only methods that are actually called, resolving virtual dispatch and inherited methods through the class hierarchy.
- Phase 2 (load closure): transitively includes supertypes of execution-reachable classes, since the JVM eagerly verifies the type hierarchy during class loading.

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

## Workflow

- Check `TODO.md` for pending work items.

### MANDATORY: Commit after every task

Every completed task MUST end with a git commit. This is not optional.

## Known Limitations

- **Reflection**: Classes loaded via reflection (e.g., `Class.forName`, `ScalaClassLoader.create`) are invisible to static analysis. They must be specified as `--entry` points.
- **JDK classes**: JDK internal classes are resolved at runtime via classloader fallback, not included in the optimized jar.
