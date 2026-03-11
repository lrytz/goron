package goron.bench.apps

import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Benchmark for circe JSON library optimization.
  *
  * Circe uses sealed ADT hierarchies and pattern matching extensively.
  * The driver exercises fold, mapObject, and JSON construction.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 10, time = 5)
@Fork(value = 1, jvmArgs = Array("-Xmx4g", "-Xms4g"))
class CirceBench {

  private var driver: BenchmarkUtils.DriverSetup = _

  private val driverCode =
    """import io.circe.Json
      |
      |object CirceDriver {
      |  def run(): AnyRef = {
      |    var result: AnyRef = null
      |    var i = 0
      |    while (i < 5000) {
      |      val json = Json.obj(
      |        "name" -> Json.fromString("hello"),
      |        "value" -> Json.fromInt(42),
      |        "items" -> Json.arr(
      |          Json.fromInt(1), Json.fromInt(2), Json.fromInt(3)
      |        ),
      |        "flag" -> Json.fromBoolean(true),
      |        "empty" -> Json.Null
      |      )
      |      json.fold(
      |        0,
      |        b => if (b) 1 else 0,
      |        n => n.toInt.getOrElse(0),
      |        s => s.length,
      |        a => a.size,
      |        o => o.size
      |      )
      |      val updated = json.mapObject(_.add("extra", Json.fromInt(99)))
      |      result = updated.noSpaces
      |      i += 1
      |    }
      |    result
      |  }
      |}
    """.stripMargin

  @Setup(Level.Trial)
  def setup(): Unit = {
    driver = BenchmarkUtils.setupDriver(driverCode, "CirceDriver",
      "io.circe:circe-core_2.13:0.14.10")
  }

  @Benchmark
  def stock(bh: Blackhole): Unit = bh.consume(driver.stock())

  @Benchmark
  def goron(bh: Blackhole): Unit = bh.consume(driver.goron())
}
