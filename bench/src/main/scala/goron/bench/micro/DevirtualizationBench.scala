package goron.bench.micro

import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Benchmark for devirtualization optimizations.
  *
  * Tests that goron can devirtualize virtual calls on abstract bases with single implementations,
  * and sealed trait pattern match dispatch.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 2, jvmArgs = Array("-Xmx2g"))
class DevirtualizationBench {

  private var singleImpl: BenchmarkUtils.DriverSetup = _
  private var sealedHierarchy: BenchmarkUtils.DriverSetup = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    singleImpl = BenchmarkUtils.setupDriver(
      """abstract class Shape {
        |  def area(size: Int): Int
        |}
        |final class Square extends Shape {
        |  override def area(size: Int): Int = size * size
        |}
        |object SingleImplDriver {
        |  def run(): AnyRef = {
        |    val shape: Shape = new Square
        |    var sum = 0
        |    var i = 0
        |    while (i < 10000) {
        |      sum += shape.area(i % 100)
        |      i += 1
        |    }
        |    Integer.valueOf(sum)
        |  }
        |}
      """.stripMargin, "SingleImplDriver")

    sealedHierarchy = BenchmarkUtils.setupDriver(
      """sealed trait Expr
        |final case class Lit(value: Int) extends Expr
        |final case class Add(a: Expr, b: Expr) extends Expr
        |final case class Mul(a: Expr, b: Expr) extends Expr
        |object SealedDriver {
        |  def eval(e: Expr): Int = e match {
        |    case Lit(v)    => v
        |    case Add(a, b) => eval(a) + eval(b)
        |    case Mul(a, b) => eval(a) * eval(b)
        |  }
        |  def run(): AnyRef = {
        |    val expr = Add(Mul(Lit(2), Lit(3)), Add(Lit(4), Lit(5)))
        |    var sum = 0
        |    var i = 0
        |    while (i < 10000) {
        |      sum += eval(expr)
        |      i += 1
        |    }
        |    Integer.valueOf(sum)
        |  }
        |}
      """.stripMargin, "SealedDriver")
  }

  @Benchmark def stockSingleImpl(bh: Blackhole): Unit = bh.consume(singleImpl.stock())
  @Benchmark def goronSingleImpl(bh: Blackhole): Unit = bh.consume(singleImpl.goron())
  @Benchmark def stockSealedHierarchy(bh: Blackhole): Unit = bh.consume(sealedHierarchy.stock())
  @Benchmark def goronSealedHierarchy(bh: Blackhole): Unit = bh.consume(sealedHierarchy.goron())
}
