package goron.bench.apps

import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Benchmark for cats-core library optimization.
  *
  * Cats uses deep trait hierarchies and the typeclass pattern extensively.
  * The driver exercises closure-heavy operations like flatMap and foldLeft.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 10, time = 5)
@Fork(value = 1, jvmArgs = Array("-Xmx4g", "-Xms4g"))
class CatsBench {

  private var driver: BenchmarkUtils.DriverSetup = _

  private val driverCode =
    """import cats.Eval
      |import cats.data.Chain
      |
      |object CatsDriver {
      |  def run(): AnyRef = {
      |    var result: AnyRef = null
      |    var i = 0
      |    while (i < 10000) {
      |      val eval = Eval.now(42)
      |        .flatMap(x => Eval.now(x + 1))
      |        .flatMap(x => Eval.now(x * 2))
      |        .map(_ + 10)
      |      result = eval.value.asInstanceOf[AnyRef]
      |      i += 1
      |    }
      |    var j = 0
      |    while (j < 1000) {
      |      var chain = Chain.one(0)
      |      var k = 1
      |      while (k < 100) {
      |        chain = chain ++ Chain.one(k)
      |        k += 1
      |      }
      |      val sum = chain.map(_ + 1).foldLeft(0)(_ + _)
      |      result = Integer.valueOf(sum)
      |      j += 1
      |    }
      |    result
      |  }
      |}
    """.stripMargin

  @Setup(Level.Trial)
  def setup(): Unit = {
    driver = BenchmarkUtils.setupDriver(driverCode, "CatsDriver",
      "org.typelevel:cats-core_2.13:2.12.0")
  }

  @Benchmark
  def stock(bh: Blackhole): Unit = bh.consume(driver.stock())

  @Benchmark
  def goron(bh: Blackhole): Unit = bh.consume(driver.goron())
}
