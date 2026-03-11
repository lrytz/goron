package goron.bench.micro

import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Benchmark for combined optimizations on collection pipelines.
  *
  * Tests map/filter/sum and foldLeft patterns that exercise inlining + closure elimination + boxing
  * optimizations together.
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

  private val mapFilterSumCode =
    """object MapFilterSumRunner {
      |  def run(n: Int): Int = {
      |    (1 to n).map(_ * 2).filter(_ > 50).sum
      |  }
      |}
      |""".stripMargin

  private val foldLeftCode =
    """object FoldLeftRunner {
      |  def run(n: Int): Int = {
      |    (1 to n).foldLeft(0) { (acc, x) =>
      |      acc + x * x
      |    }
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
    bh.consume(method.invoke(null, Integer.valueOf(1000)))
  }

  @Benchmark
  def goron(bh: Blackhole): Unit = {
    val runner = variant match {
      case "mapFilterSum" => "MapFilterSumRunner"
      case "foldLeft"     => "FoldLeftRunner"
    }
    val cls = optimizedLoader.loadClass(runner)
    val method = cls.getMethod("run", classOf[Int])
    bh.consume(method.invoke(null, Integer.valueOf(1000)))
  }
}
