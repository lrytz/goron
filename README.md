# Goron

An experimental link-time optimizer for Scala JVM bytecode.

Goron takes compiled Scala jars, applies whole-program analysis and optimization, and produces a single optimized jar.
Think of it as a linker for the Scala JVM world.
It eats through the rocks of dead code, devirtualizes method calls, and hones the code to make it run faster.

## Project status and LLM disclaimer

The current version of this codebase is heavily written by LLM coding assistants (except for the code copied from the Scala compiler).

I (`@lrytz`) am the author of the optimizer in the Scala 2.13 backend, so I am familiar with the domain and the original source code, which was copied to this repo as a starting point.
Creating a link-time optimizer for Scala JVM bytecode is an old idea ([scala-dev#396](https://github.com/scala/scala-dev/issues/396)), and Scala.js has had a link-time optimizer for a very long time with great success.
There is currently work in progress to port the Scala 2.13 optimizer to Scala 3 ([scala3#25165](https://github.com/scala/scala3/pull/25165)).

The reason this project exists now is that I picked it to learn about the capabilities of the new LLM coding agents.
I only looked at the generated code superficially.
Instead, I asked the agent to write tests, study code, implement ideas, fix bugs, improve performance etc.
I did some manual end-to-end testing, mostly by processing the Scala compiler with the linker and running the result.
I let the agent diagnose and fix it when it broke.

```
$ sbt assembly

$ cd sandbox/

$ java -jar ../target/scala-2.13/goron.jar \
  $(cs fetch 'org.scala-lang:scala-compiler:2.13.18' | sed 's/^/--input /') \
  --output scalac-optimized.jar \
  --entry scala/tools/nsc/Main \
  --entry scala/tools/nsc/reporters/ConsoleReporter \
  --verbose
Reading 5 input jar(s)...
  8431 classes, 175 resources (0.3s)
Parsing class files...
  8431 classes parsed (0.4s)
Reachability analysis...
  5460 of 8431 classes reachable (0.9s)
Closed-world analysis...
  6361 final classes, 123866 final methods, 3493 single-impl abstract methods (0.4s)
Inlining and closure optimization...
  Done (13.1s)
Local optimizations...
  5483 classes optimized (37.9s)
Dead code elimination...
  4641 classes retained, 842 removed, 28384 methods stripped (0.6s)
Serializing and writing output...
  scalac-optimized.jar (1.6s)
goron: 8431 → 4641 classes, 28384 methods stripped, 22.5M → 13.8M (55.6s)

# compile using the optimized compiler
$ java -cp scalac-optimized.jar scala.tools.nsc.Main \
  -cp $(cs fetch -p org.scala-lang:scala-library:2.13.18) \
  $(find ../scala/src/library -name '*.scala')
```

## Motivation

The Scala 2.13 compiler backend includes an optimizer: an inliner with heuristics for closures, boxing, and forwarders, a closure optimizer that eliminates lambda allocations, and a suite of local optimizations (nullness tracking, box-unbox elimination, copy propagation, dead code elimination, and more).

However, this optimizer runs at compile time, one module at a time.
It can inline methods from the module being compiled and its compile-time dependencies.
It cannot see the whole program, so it cannot determine which classes are actually used, which methods are never overridden, or which code paths are never reached.

Also, inlining from libraries at compile-time creates a tight binary dependency between the generated code and the exact library versions used in compilation.
This makes the optimizer unusable for libraries published to Maven Central, as applications will typically mix different versions at runtime.
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

- `ClassHierarchy`: shared hierarchy with precomputed indices
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

| Flag              | Description                                            |
|-------------------|--------------------------------------------------------|
| `--input <jar>`   | Input jar (repeatable)                                 |
| `--output <jar>`  | Output jar                                             |
| `--entry <class>` | Entry point class in internal name format (repeatable) |
| `--no-inline`     | Disable inlining and closure optimization              |
| `--no-dce`        | Disable dead code elimination                          |
| `--verbose`       | Show per-phase timing and statistics                   |

Build with sbt:

```
sbt assembly        # produces goron.jar
sbt test            # run tests
```

## Benchmarks

The `bench/` subproject uses JMH to compare stock bytecode against goron-optimized bytecode.
Each benchmark compiles driver code against the library under test, optimizes with goron, and runs both variants in isolated classloaders.

```
sbt "bench/Jmh/run"                           # run all benchmarks
sbt "bench/Jmh/run CatsBench"                 # run a specific benchmark
sbt "bench/Jmh/run ScalacBench -prof stack"   # with JMH profiler
```

### Results summary

State as of March 13, 2026.
The summary is AI-generated, some explanations are probably confident hallucinations.

Goron's wins come from cross-library inlining and closure elimination that the JIT cannot do on its own:

- **CatsBench -15%**: typeclass forwarders and closures inlined across library boundaries
- **CollectionPipeline.foldLeft -45%**: cross-library inlining turns `foldLeft` with a closure into a tight loop
- **CollectionPipeline.mapFilterSum -12%**: similar pipeline optimization across `map`/`filter`/`sum`
- **ScalacColdBench -8%**: cold compiler startup benefits from DCE (8431 → 4641 classes, jar 22.5M → 13.8M)
- **ParserCombinatorsBench -3%**: modest win from inlining combinator internals

Further notes:
- Neutral results on Circe, Fastparse, Spire, and the micro benchmarks (box/unbox, closure, devirtualization, inlining).
- Hot scalac and the Scala 3 compiler also show no improvement.
- For hot scalac, the compiler and scala-library are already built with the Scala 2 backend optimizer, so goron has fewer cross-library inlining opportunities left.
- For Scala 3, this is likely because its classfiles lack the `ScalaInlineInfo` attribute that goron uses for inline heuristics.
- The micro benchmarks confirm that the JIT already handles small self-contained code well — goron's value is in cross-library optimizations the JIT cannot perform.

<details>
<summary>Full JMH results</summary>

```
Benchmark                                             (sourceType)  Mode  Cnt      Score       Error  Units

g.b.apps.CatsBench.goron                                       N/A  avgt   10   1999.760 ±    14.014  us/op
g.b.apps.CatsBench.stock                                       N/A  avgt   10   2357.217 ±    15.294  us/op

g.b.apps.CirceBench.goron                                      N/A  avgt   10   4347.256 ±    61.722  us/op
g.b.apps.CirceBench.stock                                      N/A  avgt   10   4371.930 ±    37.412  us/op

g.b.apps.FastparseBench.goron                                  N/A  avgt   10  11321.770 ±   223.539  us/op
g.b.apps.FastparseBench.stock                                  N/A  avgt   10  11532.096 ±   259.055  us/op

g.b.apps.ParserCombinatorsBench.goron                          N/A  avgt   10  23779.758 ±   150.074  us/op
g.b.apps.ParserCombinatorsBench.stock                          N/A  avgt   10  24605.904 ±   109.798  us/op

g.b.apps.Scala3CompilerHotBench.goron                        hello  avgt   30     57.986 ±     1.130  ms/op
g.b.apps.Scala3CompilerHotBench.stock                        hello  avgt   30     58.433 ±     0.699  ms/op

g.b.apps.ScalacHotBench.goron                                hello  avgt   30     20.848 ±     0.416  ms/op
g.b.apps.ScalacHotBench.stock                                hello  avgt   30     21.458 ±     0.725  ms/op

g.b.apps.ScalacHotBench.goron                               scalap  avgt   30    365.923 ±    11.597  ms/op
g.b.apps.ScalacHotBench.stock                               scalap  avgt   30    370.142 ±    12.427  ms/op

g.b.apps.ScalacColdBench.goron                               hello  avgt   10    852.733 ±    64.738  ms/op
g.b.apps.ScalacColdBench.stock                               hello  avgt   10    929.359 ±    11.039  ms/op

g.b.apps.ScalacColdBench.goron                              scalap  avgt   10   3811.872 ±    94.214  ms/op
g.b.apps.ScalacColdBench.stock                              scalap  avgt   10   4137.367 ±   239.344  ms/op

g.b.apps.SpireBench.goron                                      N/A  avgt   10    425.977 ±     7.288  ms/op
g.b.apps.SpireBench.stock                                      N/A  avgt   10    428.629 ±     2.505  ms/op

g.b.micro.BoxUnboxBench.boxUnboxGoron                          N/A  avgt   20   3329.269 ±     4.265  ns/op
g.b.micro.BoxUnboxBench.boxUnboxStock                          N/A  avgt   20   3336.686 ±    12.696  ns/op

g.b.micro.BoxUnboxBench.refEliminationGoron                    N/A  avgt   20   3328.206 ±     6.884  ns/op
g.b.micro.BoxUnboxBench.refEliminationStock                    N/A  avgt   20   3333.492 ±     6.645  ns/op

g.b.micro.BoxUnboxBench.tupleUnboxGoron                        N/A  avgt   20   3330.008 ±     9.582  ns/op
g.b.micro.BoxUnboxBench.tupleUnboxStock                        N/A  avgt   20   3344.252 ±    22.506  ns/op

g.b.micro.ClosureBench.closureEliminationGoron                 N/A  avgt   20  18649.583 ±    61.124  ns/op
g.b.micro.ClosureBench.closureEliminationStock                 N/A  avgt   20  18603.472 ±    26.472  ns/op

g.b.micro.ClosureBench.closureSpecializationGoron              N/A  avgt   20   3331.747 ±     7.931  ns/op
g.b.micro.ClosureBench.closureSpecializationStock              N/A  avgt   20   3334.451 ±     9.701  ns/op

g.b.micro.CollectionPipelineBench.foldLeftGoron                N/A  avgt   20    324.512 ±     1.149  ns/op
g.b.micro.CollectionPipelineBench.foldLeftStock                N/A  avgt   20    595.449 ±     4.649  ns/op

g.b.micro.CollectionPipelineBench.mapFilterSumGoron            N/A  avgt   20   7437.561 ±    54.576  ns/op
g.b.micro.CollectionPipelineBench.mapFilterSumStock            N/A  avgt   20   8489.443 ±    67.096  ns/op

g.b.micro.DevirtualizationBench.sealedHierarchyGoron           N/A  avgt   20  54725.545 ±   118.845  ns/op
g.b.micro.DevirtualizationBench.sealedHierarchyStock           N/A  avgt   20  54811.574 ±   230.369  ns/op

g.b.micro.DevirtualizationBench.singleImplGoron                N/A  avgt   20   6305.992 ±    17.645  ns/op
g.b.micro.DevirtualizationBench.singleImplStock                N/A  avgt   20   6319.511 ±    37.553  ns/op

g.b.micro.InliningBench.inlineChainGoron                       N/A  avgt   20   3340.623 ±    11.184  ns/op
g.b.micro.InliningBench.inlineChainStock                       N/A  avgt   20   3363.621 ±    33.764  ns/op

g.b.micro.InliningBench.inlineFinalGoron                       N/A  avgt   20   8773.232 ±    48.942  ns/op
g.b.micro.InliningBench.inlineFinalStock                       N/A  avgt   20   8756.639 ±    48.629  ns/op
```

</details>