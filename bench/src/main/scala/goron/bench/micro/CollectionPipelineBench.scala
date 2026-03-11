package goron.bench.micro

import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Benchmark for combined optimizations on pipeline-style code.
  *
  * Tests chained higher-order final method calls that exercise inlining + closure elimination + boxing
  * optimizations together, simulating the pattern of collection pipelines.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 2, jvmArgs = Array("-Xmx2g"))
class CollectionPipelineBench {

  private var stockLoader: ClassLoader = _
  private var optimizedLoader: ClassLoader = _

  // Simulates map + filter + sum pipeline with self-contained higher-order methods
  private val mapFilterSumCode =
    """object Pipeline {
      |  @inline final def map(start: Int, end: Int, f: Int => Int, g: Int => Unit): Unit = {
      |    var i = start
      |    while (i < end) { g(f(i)); i += 1 }
      |  }
      |  @inline final def filterSum(x: Int, pred: Int => Boolean, acc: Array[Int]): Unit = {
      |    if (pred(x)) acc(0) += x
      |  }
      |}
      |
      |object MapFilterSumRunner {
      |  def run(n: Int): Int = {
      |    val acc = new Array[Int](1)
      |    Pipeline.map(1, n, x => x * 2, x => Pipeline.filterSum(x, _ > 50, acc))
      |    acc(0)
      |  }
      |}
      |""".stripMargin

  // Simulates foldLeft with a self-contained higher-order method
  private val foldLeftCode =
    """object Folder {
      |  @inline final def foldLeft(start: Int, end: Int, zero: Int, f: (Int, Int) => Int): Int = {
      |    var acc = zero
      |    var i = start
      |    while (i < end) { acc = f(acc, i); i += 1 }
      |    acc
      |  }
      |}
      |
      |object FoldLeftRunner {
      |  def run(n: Int): Int = {
      |    Folder.foldLeft(1, n, 0, (acc, x) => acc + x * x)
      |  }
      |}
      |""".stripMargin

  @Param(Array("mapFilterSum", "foldLeft"))
  var variant: String = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    val code = variant match {
      case "mapFilterSum" => mapFilterSumCode
      case "foldLeft"     => foldLeftCode
    }
    val (stock, optimized) = BenchmarkUtils.compileAndOptimize(code)
    stockLoader = BenchmarkUtils.classLoaderFromBytes(stock)
    optimizedLoader = BenchmarkUtils.classLoaderFromBytes(optimized)
  }

  @Benchmark
  def stock(bh: Blackhole): Unit = {
    val runner = variant match {
      case "mapFilterSum" => "MapFilterSumRunner"
      case "foldLeft"     => "FoldLeftRunner"
    }
    val cls = stockLoader.loadClass(runner)
    val method = cls.getMethod("run", classOf[Int])
    bh.consume(method.invoke(null, Integer.valueOf(10000)))
  }

  @Benchmark
  def goron(bh: Blackhole): Unit = {
    val runner = variant match {
      case "mapFilterSum" => "MapFilterSumRunner"
      case "foldLeft"     => "FoldLeftRunner"
    }
    val cls = optimizedLoader.loadClass(runner)
    val method = cls.getMethod("run", classOf[Int])
    bh.consume(method.invoke(null, Integer.valueOf(10000)))
  }
}
