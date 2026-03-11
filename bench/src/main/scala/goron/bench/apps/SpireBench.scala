package goron.bench.apps

import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Benchmark for spire numeric library optimization.
  *
  * Spire uses numeric typeclasses with heavy implicit dispatch, specialization,
  * and generic boxing. The driver exercises devirtualization of typeclass methods,
  * boxing elimination on generic numeric values, and closure-heavy coefficient mapping.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 10, time = 5)
@Fork(value = 1, jvmArgs = Array("-Xmx4g", "-Xms4g"))
class SpireBench {

  private var driver: BenchmarkUtils.DriverSetup = _

  private val driverCode =
    """import spire.math._
      |import spire.implicits._
      |
      |object SpireDriver {
      |  def run(): AnyRef = {
      |    // Heavy rational arithmetic: simulate matrix dot product style computation
      |    // Each Rational op involves GCD, normalization, typeclass dispatch
      |    var sum = Rational(0)
      |    var i = 0
      |    while (i < 200) {
      |      var j = 0
      |      while (j < 200) {
      |        val r = Rational(i * j + 1, i + j + 1)
      |        sum = sum + r * Rational(j + 1, i + 1)
      |        j += 1
      |      }
      |      i += 1
      |    }
      |
      |    // Polynomial: build degree-20 poly and evaluate at 10K points
      |    val coeffs = new Array[Double](20)
      |    var c = 0
      |    while (c < 20) { coeffs(c) = (c + 1).toDouble; c += 1 }
      |    val poly = Polynomial.dense(coeffs)
      |    var polySum = 0.0
      |    var k = 0
      |    while (k < 10000) {
      |      polySum += poly(k.toDouble * 0.001)
      |      k += 1
      |    }
      |
      |    // Complex number accumulation
      |    var cx = Complex(0.0, 0.0)
      |    var m = 0
      |    while (m < 50000) {
      |      val angle = m.toDouble * 0.001
      |      cx = cx + Complex(spire.math.cos(angle), spire.math.sin(angle))
      |      m += 1
      |    }
      |
      |    java.lang.Double.valueOf(sum.toDouble + polySum + cx.real)
      |  }
      |}
    """.stripMargin

  @Setup(Level.Trial)
  def setup(): Unit = {
    driver = BenchmarkUtils.setupDriver(driverCode, "SpireDriver",
      "org.typelevel:spire_2.13:0.18.0")
  }

  @Benchmark
  def stock(bh: Blackhole): Unit = bh.consume(driver.stock())

  @Benchmark
  def goron(bh: Blackhole): Unit = bh.consume(driver.goron())
}
