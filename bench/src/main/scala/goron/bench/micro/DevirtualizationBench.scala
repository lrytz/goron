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

  private var stockLoader: ClassLoader = _
  private var optimizedLoader: ClassLoader = _

  private val singleImplCode =
    """abstract class Shape {
      |  def area(size: Int): Int
      |}
      |
      |final class Square extends Shape {
      |  override def area(size: Int): Int = size * size
      |}
      |
      |object SingleImplRunner {
      |  def run(n: Int): Int = {
      |    val shape: Shape = new Square
      |    var sum = 0
      |    var i = 0
      |    while (i < n) {
      |      sum += shape.area(i % 100)
      |      i += 1
      |    }
      |    sum
      |  }
      |}
      |""".stripMargin

  private val sealedHierarchyCode =
    """sealed trait Expr
      |final case class Lit(value: Int) extends Expr
      |final case class Add(a: Expr, b: Expr) extends Expr
      |final case class Mul(a: Expr, b: Expr) extends Expr
      |
      |object ExprEval {
      |  def eval(e: Expr): Int = e match {
      |    case Lit(v)    => v
      |    case Add(a, b) => eval(a) + eval(b)
      |    case Mul(a, b) => eval(a) * eval(b)
      |  }
      |}
      |
      |object SealedRunner {
      |  def run(n: Int): Int = {
      |    val expr = Add(Mul(Lit(2), Lit(3)), Add(Lit(4), Lit(5)))
      |    var sum = 0
      |    var i = 0
      |    while (i < n) {
      |      sum += ExprEval.eval(expr)
      |      i += 1
      |    }
      |    sum
      |  }
      |}
      |""".stripMargin

  @Param(Array("singleImpl", "sealedHierarchy"))
  var variant: String = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    val code = variant match {
      case "singleImpl"      => singleImplCode
      case "sealedHierarchy" => sealedHierarchyCode
    }
    val (stock, optimized) = BenchmarkUtils.compileAndOptimize(code)
    stockLoader = BenchmarkUtils.classLoaderFromBytes(stock)
    optimizedLoader = BenchmarkUtils.classLoaderFromBytes(optimized)
  }

  @Benchmark
  def stock(bh: Blackhole): Unit = {
    val runner = variant match {
      case "singleImpl"      => "SingleImplRunner"
      case "sealedHierarchy" => "SealedRunner"
    }
    val cls = stockLoader.loadClass(runner)
    val method = cls.getMethod("run", classOf[Int])
    bh.consume(method.invoke(null, Integer.valueOf(10000)))
  }

  @Benchmark
  def goron(bh: Blackhole): Unit = {
    val runner = variant match {
      case "singleImpl"      => "SingleImplRunner"
      case "sealedHierarchy" => "SealedRunner"
    }
    val cls = optimizedLoader.loadClass(runner)
    val method = cls.getMethod("run", classOf[Int])
    bh.consume(method.invoke(null, Integer.valueOf(10000)))
  }
}
