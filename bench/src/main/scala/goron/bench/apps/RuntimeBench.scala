package goron.bench.apps

import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._

import java.io.File
import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

/** Runtime performance benchmark: compiles the "Are We Fast Yet" benchmarks
  * with a Scala compiler, then runs the compiled code under JMH to measure
  * how goron optimization affects runtime performance.
  *
  * The benchmark sources (vendored from lampepfl/scala3-benchmarks) are compiled
  * once at trial setup. Each iteration invokes a benchmark method via reflection
  * on either stock or goron-optimized classfiles.
  *
  * Run with: sbt "bench/Jmh/run RuntimeBench"
  */
@BenchmarkMode(Array(Mode.SingleShotTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 20, batchSize = 10)
@Measurement(iterations = 20, batchSize = 10)
@Fork(value = 1, jvmArgs = Array("-Xmx2g", "-Xms2g"))
class RuntimeBench {

  private var stockCl: URLClassLoader = _
  private var goronCl: URLClassLoader = _
  private var stock: Map[String, () => Any] = _
  private var goron: Map[String, () => Any] = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    val compilerJars = BenchmarkUtils.resolve("org.scala-lang:scala-compiler:2.13.18")
    val sourceFiles = RuntimeBenchSources.extractSources()
    val benchJar = RuntimeBenchSources.compileSources(sourceFiles, compilerJars)
    val scalaLibrary = compilerJars.find(_.getName.contains("scala-library")).get

    val goronJar = BenchmarkUtils.optimizeJars(
      Array(benchJar, scalaLibrary),
      List(
        "bounce/BounceBenchmark$", "richards/Richards$", "deltablue/DeltaBlue$",
        "tracer/Tracer$", "json/JsonBenchmark$", "cd/CDBenchmark$",
        "kmeans/KmeansBenchmark$", "gcbench/GCBenchBenchmark$",
        "list/ListBenchmark$", "mandelbrot/MandelbrotBenchmark$",
        "nbody/NbodyBenchmark$", "permute/PermuteBenchmark$",
        "queens/QueensBenchmark$", "brainfuck/BrainfuckBenchmark$"
      )
    )

    stockCl = new URLClassLoader(
      Array(benchJar.toURI.toURL, scalaLibrary.toURI.toURL),
      ClassLoader.getSystemClassLoader.getParent
    )
    goronCl = new URLClassLoader(
      Array(goronJar.toURI.toURL),
      ClassLoader.getSystemClassLoader.getParent
    )

    stock = resolveAll(stockCl)
    goron = resolveAll(goronCl)
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    if (stockCl != null) stockCl.close()
    if (goronCl != null) goronCl.close()
  }

  private def resolveAll(cl: URLClassLoader): Map[String, () => Any] = {
    val jsonInput = {
      val (module, cls) = loadModule(cl, "json.JsonBenchmark")
      cls.getMethod("input").invoke(module).asInstanceOf[String]
    }

    Map(
      "bounce"    -> makeRunner(cl, "bounce.BounceBenchmark", "run", classOf[Int] -> (100: java.lang.Integer)),
      "richards"  -> makeRunner(cl, "richards.Richards", "run"),
      "deltablue" -> makeRunner(cl, "deltablue.DeltaBlue", "run"),
      "tracer"    -> makeRunner(cl, "tracer.Tracer", "run"),
      "json"      -> makeRunner(cl, "json.JsonBenchmark", "run", classOf[String] -> jsonInput),
      "cd"        -> makeRunner(cl, "cd.CDBenchmark", "run", classOf[Int] -> (100: java.lang.Integer)),
      "kmeans"    -> makeRunner(cl, "kmeans.KmeansBenchmark", "run", classOf[Int] -> (100000: java.lang.Integer)),
      "gcbench"   -> makeRunner(cl, "gcbench.GCBenchBenchmark", "run")
    )
  }

  /** Load a Scala object module and resolve a no-arg method on it. */
  private def makeRunner(cl: URLClassLoader, className: String, methodName: String): () => Any = {
    val (module, cls) = loadModule(cl, className)
    val method = cls.getMethod(methodName)
    () => method.invoke(module)
  }

  /** Load a Scala object module and resolve a single-arg method on it. */
  private def makeRunner(cl: URLClassLoader, className: String, methodName: String,
                         arg: (Class[_], AnyRef)): () => Any = {
    val (module, cls) = loadModule(cl, className)
    val method = cls.getMethod(methodName, arg._1)
    val argVal = arg._2
    () => method.invoke(module, argVal)
  }

  /** Load the MODULE$ singleton for a Scala object. Tries className$ first (module class),
    * then className (which has MODULE$ on some compilation targets).
    */
  private def loadModule(cl: URLClassLoader, className: String): (AnyRef, Class[_]) = {
    val moduleCls = cl.loadClass(className + "$")
    val module = moduleCls.getField("MODULE$").get(null)
    (module, moduleCls)
  }

  // --- Stock benchmarks ---
  @Benchmark def stockBounce(): Any    = stock("bounce")()
  @Benchmark def stockRichards(): Any  = stock("richards")()
  @Benchmark def stockDeltaBlue(): Any = stock("deltablue")()
  @Benchmark def stockTracer(): Any    = stock("tracer")()
  @Benchmark def stockJson(): Any      = stock("json")()
  @Benchmark def stockCd(): Any        = stock("cd")()
  @Benchmark def stockKmeans(): Any    = stock("kmeans")()
  @Benchmark def stockGcBench(): Any   = stock("gcbench")()

  // --- Goron benchmarks ---
  @Benchmark def goronBounce(): Any    = goron("bounce")()
  @Benchmark def goronRichards(): Any  = goron("richards")()
  @Benchmark def goronDeltaBlue(): Any = goron("deltablue")()
  @Benchmark def goronTracer(): Any    = goron("tracer")()
  @Benchmark def goronJson(): Any      = goron("json")()
  @Benchmark def goronCd(): Any        = goron("cd")()
  @Benchmark def goronKmeans(): Any    = goron("kmeans")()
  @Benchmark def goronGcBench(): Any   = goron("gcbench")()
}
