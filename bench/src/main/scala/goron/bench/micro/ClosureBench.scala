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

  private var closureElim: BenchmarkUtils.DriverSetup = _
  private var closureSpec: BenchmarkUtils.DriverSetup = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    closureElim = BenchmarkUtils.setupDriver(
      """object ClosureElimDriver {
        |  object HOHelper {
        |    @inline final def transform(x: Int, f: Int => Int): Int = f(x)
        |    @inline final def combine(a: Int, b: Int, f: (Int, Int) => Int): Int = f(a, b)
        |  }
        |  def run(): AnyRef = {
        |    var sum = 0
        |    var i = 0
        |    while (i < 10000) {
        |      sum += HOHelper.transform(i, x => x * x + 1)
        |      sum += HOHelper.combine(i, sum, (a, b) => a + b * 2)
        |      i += 1
        |    }
        |    Integer.valueOf(sum)
        |  }
        |}
      """.stripMargin, "ClosureElimDriver")

    closureSpec = BenchmarkUtils.setupDriver(
      """object ClosureSpecDriver {
        |  object SpecHelper {
        |    @inline final def applyTwice(x: Int, f: Int => Int): Int = f(f(x))
        |    @inline final def fold(start: Int, end: Int, acc: Int, f: (Int, Int) => Int): Int = {
        |      var result = acc
        |      var i = start
        |      while (i < end) {
        |        result = f(result, i)
        |        i += 1
        |      }
        |      result
        |    }
        |  }
        |  def run(): AnyRef = {
        |    val doubled = SpecHelper.applyTwice(10000, _ * 2)
        |    Integer.valueOf(SpecHelper.fold(0, 10000, doubled, _ + _))
        |  }
        |}
      """.stripMargin, "ClosureSpecDriver")
  }

  @Benchmark def closureEliminationStock(bh: Blackhole): Unit = bh.consume(closureElim.stock())
  @Benchmark def closureEliminationGoron(bh: Blackhole): Unit = bh.consume(closureElim.goron())
  @Benchmark def closureSpecializationStock(bh: Blackhole): Unit = bh.consume(closureSpec.stock())
  @Benchmark def closureSpecializationGoron(bh: Blackhole): Unit = bh.consume(closureSpec.goron())
}
