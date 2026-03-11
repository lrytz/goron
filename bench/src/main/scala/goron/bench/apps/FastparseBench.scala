package goron.bench.apps

import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Benchmark for fastparse library optimization.
  *
  * Fastparse is extremely closure-heavy. The driver exercises actual parsing
  * with combinators like rep, map, and sep.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 10, time = 5)
@Fork(value = 1, jvmArgs = Array("-Xmx4g", "-Xms4g"))
class FastparseBench {

  private var driver: BenchmarkUtils.DriverSetup = _

  private val driverCode =
    """object FastparseDriver {
      |  import fastparse._, NoWhitespace._
      |
      |  def digit[$: P]: P[Unit] = P(CharIn("0-9"))
      |  def number[$: P]: P[Int] = P(digit.rep(1).!.map(_.toInt))
      |  def csv[$: P]: P[Seq[Int]] = P(number.rep(sep = ","))
      |
      |  def run(): AnyRef = {
      |    var result: AnyRef = null
      |    val input = (1 to 50).mkString(",")
      |    var i = 0
      |    while (i < 5000) {
      |      result = fastparse.parse(input, csv(_)).asInstanceOf[AnyRef]
      |      i += 1
      |    }
      |    result
      |  }
      |}
    """.stripMargin

  @Setup(Level.Trial)
  def setup(): Unit = {
    driver = BenchmarkUtils.setupDriver(driverCode, "FastparseDriver",
      "com.lihaoyi:fastparse_2.13:3.1.1")
  }

  @Benchmark
  def stock(bh: Blackhole): Unit = bh.consume(driver.stock())

  @Benchmark
  def goron(bh: Blackhole): Unit = bh.consume(driver.goron())
}
