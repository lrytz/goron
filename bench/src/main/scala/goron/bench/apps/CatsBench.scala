package goron.bench.apps

import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit

/** Benchmark for cats-core library optimization.
  *
  * Cats uses deep trait hierarchies and the typeclass pattern extensively.
  * The workload exercises Eval creation/evaluation and Chain construction,
  * which involve sealed ADT dispatch and typeclass resolution.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 10, time = 5)
@Fork(value = 1, jvmArgs = Array("-Xmx4g", "-Xms4g"))
class CatsBench {

  private var stockLoader: URLClassLoader = _
  private var optimizedLoader: URLClassLoader = _

  // Cached reflection handles
  private var stockHandles: CatsHandles = _
  private var goronHandles: CatsHandles = _

  private class CatsHandles(
      val evalModule: AnyRef,
      val nowMethod: java.lang.reflect.Method,
      val valueMethod: java.lang.reflect.Method,
      val chainModule: AnyRef,
      val oneMethod: java.lang.reflect.Method,
      val emptyMethod: java.lang.reflect.Method,
      val concatMethod: java.lang.reflect.Method,
      val nonEmptyMethod: java.lang.reflect.Method,
      val toStringMethod: java.lang.reflect.Method
  )

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

    stockHandles = setupHandles(stockLoader)
    goronHandles = setupHandles(optimizedLoader)
  }

  private def setupHandles(cl: ClassLoader): CatsHandles = {
    val evalClass = cl.loadClass("cats.Eval$")
    val evalModule = evalClass.getField("MODULE$").get(null)
    val evalInstanceClass = cl.loadClass("cats.Eval")
    val nowMethod = evalClass.getMethod("now", classOf[Object])
    val valueMethod = evalInstanceClass.getMethod("value")

    val chainClass = cl.loadClass("cats.data.Chain$")
    val chainModule = chainClass.getField("MODULE$").get(null)
    val oneMethod = chainClass.getMethod("one", classOf[Object])
    val emptyMethod = chainClass.getMethods.find(m => m.getName == "empty" && m.getParameterCount == 0).orNull
    val chainInstanceClass = cl.loadClass("cats.data.Chain")
    val concatMethod = chainInstanceClass.getMethods.find(m => m.getName == "concat" && m.getParameterCount == 1).orNull
    val nonEmptyMethod = chainInstanceClass.getMethods.find(m => m.getName == "nonEmpty" && m.getParameterCount == 0).orNull
    val toStringMethod = chainInstanceClass.getMethod("toString")

    new CatsHandles(evalModule, nowMethod, valueMethod, chainModule, oneMethod,
      emptyMethod, concatMethod, nonEmptyMethod, toStringMethod)
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    if (stockLoader != null) stockLoader.close()
    if (optimizedLoader != null) optimizedLoader.close()
  }

  @Benchmark
  def stock(bh: Blackhole): Unit = {
    bh.consume(runWorkload(stockHandles))
  }

  @Benchmark
  def goron(bh: Blackhole): Unit = {
    bh.consume(runWorkload(goronHandles))
  }

  private def runWorkload(h: CatsHandles): AnyRef = {
    var result: AnyRef = null

    // Exercise Eval creation and evaluation (sealed ADT dispatch)
    for (_ <- 0 until 10000) {
      val eval = h.nowMethod.invoke(h.evalModule, Integer.valueOf(42))
      result = h.valueMethod.invoke(eval)
    }

    // Exercise Chain construction and dispatch (sealed ADT + concatenation)
    if (h.concatMethod != null) {
      for (_ <- 0 until 1000) {
        var chain = h.oneMethod.invoke(h.chainModule, Integer.valueOf(0))
        for (i <- 1 until 100) {
          val next = h.oneMethod.invoke(h.chainModule, Integer.valueOf(i))
          chain = h.concatMethod.invoke(chain, next)
        }
        if (h.nonEmptyMethod != null) h.nonEmptyMethod.invoke(chain)
        result = chain
      }
    }

    result
  }
}
