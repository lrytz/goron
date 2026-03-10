# Goron

A link-time optimizer for Scala JVM bytecode.

Goron takes compiled Scala jars, applies whole-program analysis and optimization, and produces a single optimized jar.
Think of it as a linker for the Scala JVM world.
It eats through the rocks of dead code, devirtualizes method calls, and carves the input down to what your program actually needs.

## Motivation

The Scala 2.13 compiler backend includes an optimizer: an inliner with heuristics for closures, boxing, and forwarders, a closure optimizer that eliminates lambda allocations, and a suite of local optimizations (nullness tracking, box-unbox elimination, copy propagation, dead code elimination, and more).

However, this optimizer runs at compile time, one module at a time.
It can inline methods from the module being compiled and its compile-time dependencies.
It cannot see the whole program, so it cannot determine which classes are actually used, which methods are never overridden, or which code paths are never reached.

Also, inlining from libraries at compile-time creates a tight binary dependency between the generated code and the exact library versions used in compilation.
This makes the optimizer unusable for libraries published to Maven Central, as applications will typically use different versions at runtime.
Therefore, typically only the application code is optimized, while external library code is not.

Goron lifts the Scala optimizer to link time: it reads all the jars that make up a program, performs whole-program analysis, and then runs the optimization passes with more visibility.

- Classes and methods that are not reachable from the program entry points can be removed
- Inlining can be applied everywhere, in any method from any jar
- When the full class hierarchy is known, non-final methods that are never overridden are available for inlining

## Building from the compiler optimizer

Goron's optimizer is a fork of the Scala 2.13.18 backend optimizer.
The compiler code was adapted to work outside the compiler's symbol table and global state.
Key changes to the forked code:

- `BTypes` built from classfiles only. In the compiler, `BTypes` are constructed from both compiler symbols and classfiles. Goron only has classfiles, so all type construction goes through `BTypesFromClassfile`.
- CompilerSettings decoupled from the compiler's Global class.

## What Goron adds

The following components are new, they do not exist in the Scala compiler:

- `ClassHierarchy.scala`: shared hierarchy with precomputed indices
- `ReachabilityAnalysis`: Two-phase whole-program reachability (RTA)
- `ClosedWorldAnalysis`: Effectively-final class/method analysis
- `Goron`: Optimization pipeline orchestration
- `GoronCli`: Command-line interface
- `GoronConfig`: Configuration
- `JarIO`: Jar reading/writing
- `Classpath`: Classpath abstraction for type resolution

### Reachability analysis

A two-phase Rapid Type Analysis (RTA) starting from user-specified entry points:

1. Method-level BFS follows method calls at method granularity. If a class has 100 methods but only 1 is called, only that method's references are followed. Tracks class instantiation (including via `LambdaMetafactory` `invokedynamic`), virtual call dispatch, and interface resolution.

2. Load closure ensures the JVM can actually load retained classes. For each execution-reachable class, all types referenced in method bodies, descriptors, stack map frames, and exception tables are transitively included. These "load-reachable" classes are needed for verification but their own method bodies are not traversed.

After optimization, a second pass runs the same analysis on the optimized bytecode, then strips unreachable classes entirely and removes unreachable methods from surviving classes.

### Closed-world analysis

When all input jars are provided, the full class hierarchy is known.
Goron computes which classes have no subclasses (effectively final) and which methods have no overrides (effectively final), then updates the `ScalaInlineInfo` classfile attribute.
The inliner reads these attributes, so it can now inline virtual calls on non-leaf classes that the compile-time optimizer had to leave alone.

### Class hierarchy with precomputed indices

`ClassHierarchy` is built once and shared across reachability analysis, closed-world analysis, and the optimizer.
It precomputes transitive supertypes for O(1) subclass checks, indexes instantiated classes by supertype, and provides per-class method lookup maps.

## Pipeline

```
Read input jars
    │
    ▼
Parse ClassNodes (ASM)
    │
    ▼
Build ClassHierarchy
    │
    ▼
Reachability analysis ──► filter to reachable classes
    │
    ▼
Closed-world analysis ──► mark effectively-final in InlineInfo
    │
    ▼
Global optimizations (inliner + closure optimizer, up to 10 rounds)
    │
    ▼
Local optimizations (nullness, box-unbox, copy propagation, dead code, ...)
    │
    ▼
Dead code elimination ──► strip unreachable classes and methods
    │
    ▼
Serialize and write output jar
```

## Usage

```
goron --input app.jar --input scala-library.jar --input scala-compiler.jar \
      --output optimized.jar \
      --entry com/example/Main \
      --verbose
```

Options:

| Flag | Description |
|------|-------------|
| `--input <jar>` | Input jar (repeatable) |
| `--output <jar>` | Output jar |
| `--entry <class>` | Entry point class in internal name format (repeatable) |
| `--no-inline` | Disable inlining and closure optimization |
| `--no-dce` | Disable dead code elimination |
| `--verbose` | Show per-phase timing and statistics |

Build with sbt:

```
sbt assembly        # produces goron.jar
sbt test            # run tests
```

## Benchmarks

The `bench/` subproject uses JMH to compare stock Scala 2.13.18 compilation performance against goron-optimized compilation:

```
sbt "bench/Jmh/run ScalacBenchmark"
```

Two workloads: a small "hello world" file and the `scalap` module (~30 source files).
