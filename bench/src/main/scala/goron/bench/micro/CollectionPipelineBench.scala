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

  private var mapFilterSum: BenchmarkUtils.DriverSetup = _
  private var foldLeft: BenchmarkUtils.DriverSetup = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    mapFilterSum = BenchmarkUtils.setupDriver(
      """object MapFilterSumDriver {
        |  def run(): AnyRef = {
        |    Integer.valueOf((1 to 1000).map(_ * 2).filter(_ > 50).sum)
        |  }
        |}
      """.stripMargin, "MapFilterSumDriver")

    foldLeft = BenchmarkUtils.setupDriver(
      """object FoldLeftDriver {
        |  def run(): AnyRef = {
        |    Integer.valueOf((1 to 1000).foldLeft(0) { (acc, x) => acc + x * x })
        |  }
        |}
      """.stripMargin, "FoldLeftDriver")
  }

  @Benchmark def stockMapFilterSum(bh: Blackhole): Unit = bh.consume(mapFilterSum.stock())
  @Benchmark def goronMapFilterSum(bh: Blackhole): Unit = bh.consume(mapFilterSum.goron())
  @Benchmark def stockFoldLeft(bh: Blackhole): Unit = bh.consume(foldLeft.stock())
  @Benchmark def goronFoldLeft(bh: Blackhole): Unit = bh.consume(foldLeft.goron())
}
