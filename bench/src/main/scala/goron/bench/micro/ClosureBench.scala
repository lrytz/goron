package goron.bench.micro

import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Benchmark for closure elimination and specialization.
  *
  * Tests that goron can inline higher-order final methods and eliminate closure allocations,
  * and that closures with primitive args use specialized apply paths.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 2, jvmArgs = Array("-Xmx2g"))
class ClosureBench {

  private var stockLoader: ClassLoader = _
  private var optimizedLoader: ClassLoader = _

  private val closureEliminationCode =
    """object HOHelper {
      |  @inline final def transform(x: Int, f: Int => Int): Int = f(x)
      |  @inline final def combine(a: Int, b: Int, f: (Int, Int) => Int): Int = f(a, b)
      |}
      |
      |object ClosureElimRunner {
      |  def run(n: Int): Int = {
      |    var sum = 0
      |    var i = 0
      |    while (i < n) {
      |      sum += HOHelper.transform(i, x => x * x + 1)
      |      sum += HOHelper.combine(i, sum, (a, b) => a + b * 2)
      |      i += 1
      |    }
      |    sum
      |  }
      |}
      |""".stripMargin

  private val closureSpecializationCode =
    """object SpecHelper {
      |  @inline final def applyTwice(x: Int, f: Int => Int): Int = f(f(x))
      |  @inline final def fold(start: Int, end: Int, acc: Int, f: (Int, Int) => Int): Int = {
      |    var result = acc
      |    var i = start
      |    while (i < end) {
      |      result = f(result, i)
      |      i += 1
      |    }
      |    result
      |  }
      |}
      |
      |object ClosureSpecRunner {
      |  def run(n: Int): Int = {
      |    val doubled = SpecHelper.applyTwice(n, _ * 2)
      |    SpecHelper.fold(0, n, doubled, _ + _)
      |  }
      |}
      |""".stripMargin

  @Param(Array("closureElimination", "closureSpecialization"))
  var variant: String = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    val code = variant match {
      case "closureElimination"     => closureEliminationCode
      case "closureSpecialization"  => closureSpecializationCode
    }
    val (stock, optimized) = BenchmarkUtils.compileAndOptimize(code)
    stockLoader = BenchmarkUtils.classLoaderFromBytes(stock)
    optimizedLoader = BenchmarkUtils.classLoaderFromBytes(optimized)
  }

  @Benchmark
  def stock(bh: Blackhole): Unit = {
    val runner = variant match {
      case "closureElimination"    => "ClosureElimRunner"
      case "closureSpecialization" => "ClosureSpecRunner"
    }
    val cls = stockLoader.loadClass(runner)
    val method = cls.getMethod("run", classOf[Int])
    bh.consume(method.invoke(null, Integer.valueOf(10000)))
  }

  @Benchmark
  def goron(bh: Blackhole): Unit = {
    val runner = variant match {
      case "closureElimination"    => "ClosureElimRunner"
      case "closureSpecialization" => "ClosureSpecRunner"
    }
    val cls = optimizedLoader.loadClass(runner)
    val method = cls.getMethod("run", classOf[Int])
    bh.consume(method.invoke(null, Integer.valueOf(10000)))
  }
}
