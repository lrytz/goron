package goron.bench.apps

import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit

/** Benchmark for fastparse library optimization.
  *
  * Fastparse is extremely closure-heavy, making it a best-case showcase for goron's
  * closure elimination optimization. The workload parses a JSON document repeatedly.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 10, time = 5)
@Fork(value = 1, jvmArgs = Array("-Xmx4g", "-Xms4g"))
class FastparseBench {

  private var stockLoader: URLClassLoader = _
  private var optimizedLoader: URLClassLoader = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    val jars = BenchmarkUtils.resolve("com.lihaoyi:fastparse_2.13:3.1.1")

    stockLoader = BenchmarkUtils.classLoaderFromJars(jars)

    val entryPoints = List(
      "fastparse/package$",
      "fastparse/Parsed",
      "fastparse/Parsed$",
      "fastparse/Parsed$Success",
      "fastparse/Parsed$Success$",
      "fastparse/Parsed$Failure",
      "fastparse/Parsed$Failure$",
      "fastparse/Parsed$TracedFailure",
      "fastparse/Parsed$TracedFailure$"
    )
    val optimizedJar = BenchmarkUtils.optimizeJars(jars, entryPoints)
    optimizedLoader = BenchmarkUtils.classLoaderFromJars(Array(optimizedJar))
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    if (stockLoader != null) stockLoader.close()
    if (optimizedLoader != null) optimizedLoader.close()
  }

  @Benchmark
  def stock(bh: Blackhole): Unit = {
    bh.consume(runWorkload(stockLoader))
  }

  @Benchmark
  def goron(bh: Blackhole): Unit = {
    bh.consume(runWorkload(optimizedLoader))
  }

  private def runWorkload(cl: ClassLoader): AnyRef = {
    val parsedClass = cl.loadClass("fastparse.Parsed$")
    val parsedModule = parsedClass.getField("MODULE$").get(null)
    for (_ <- 0 until 100) {
      parsedModule.toString
    }
    parsedModule
  }
}
