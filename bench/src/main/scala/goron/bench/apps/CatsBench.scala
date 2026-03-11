package goron.bench.apps

import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit

/** Benchmark for cats-core library optimization.
  *
  * Cats uses deep trait hierarchies and the typeclass pattern extensively,
  * making it a showcase for goron's devirtualization and inlining optimizations.
  * The workload exercises Eval trampolining and Chain operations.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 10, time = 5)
@Fork(value = 1, jvmArgs = Array("-Xmx4g", "-Xms4g"))
class CatsBench {

  private var stockLoader: URLClassLoader = _
  private var optimizedLoader: URLClassLoader = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    val jars = BenchmarkUtils.resolve("org.typelevel:cats-core_2.13:2.12.0")

    stockLoader = BenchmarkUtils.classLoaderFromJars(jars)

    val entryPoints = List(
      "cats/Eval$",
      "cats/Eval",
      "cats/Eval$Now",
      "cats/Eval$Later",
      "cats/Eval$Always",
      "cats/Eval$Defer",
      "cats/Eval$FlatMap",
      "cats/Eval$Memoize",
      "cats/data/Chain$",
      "cats/data/Chain",
      "cats/data/Chain$Empty$",
      "cats/data/Chain$Singleton",
      "cats/data/Chain$Append",
      "cats/data/Chain$Wrap"
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
    // Exercise Eval trampolining via reflection
    val evalClass = cl.loadClass("cats.Eval$")
    val evalModule = evalClass.getField("MODULE$").get(null)

    // Eval.now(42)
    val nowMethod = evalClass.getMethod("now", classOf[Object])
    val eval42 = nowMethod.invoke(evalModule, Integer.valueOf(42))

    // Call .value repeatedly to exercise the trampoline
    val evalInstanceClass = cl.loadClass("cats.Eval")
    val valueMethod = evalInstanceClass.getMethod("value")

    var result: AnyRef = null
    for (_ <- 0 until 1000) {
      result = valueMethod.invoke(eval42)
    }

    // Exercise Chain operations
    val chainClass = cl.loadClass("cats.data.Chain$")
    val chainModule = chainClass.getField("MODULE$").get(null)
    val oneMethod = chainClass.getMethod("one", classOf[Object])

    val chain1 = oneMethod.invoke(chainModule, Integer.valueOf(1))
    val chainInstanceClass = cl.loadClass("cats.data.Chain")
    val appendMethod = chainInstanceClass.getMethods.find(_.getName == "append").orNull

    if (appendMethod != null) {
      var chain = chain1
      for (i <- 2 to 100) {
        val next = oneMethod.invoke(chainModule, Integer.valueOf(i))
        chain = appendMethod.invoke(chain, next)
      }
      result = chain
    }

    result
  }
}
