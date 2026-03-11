package goron.bench.micro

import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Benchmark for method inlining optimizations.
  *
  * Tests cross-class final method inlining and method inlining chains.
  * Goron should inline these calls, eliminating virtual dispatch overhead.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 2, jvmArgs = Array("-Xmx2g"))
class InliningBench {

  private var inlineFinal: BenchmarkUtils.DriverSetup = _
  private var inlineChain: BenchmarkUtils.DriverSetup = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    inlineFinal = BenchmarkUtils.setupDriver(
      """object InlineFinalDriver {
        |  object MathHelper {
        |    @inline final def square(x: Int): Int = x * x
        |    @inline final def cube(x: Int): Int = x * x * x
        |    @inline final def add(a: Int, b: Int): Int = a + b
        |  }
        |  def run(): AnyRef = {
        |    var sum = 0
        |    var i = 0
        |    while (i < 10000) {
        |      sum = MathHelper.add(sum, MathHelper.square(i))
        |      sum = MathHelper.add(sum, MathHelper.cube(i % 10))
        |      i += 1
        |    }
        |    Integer.valueOf(sum)
        |  }
        |}
      """.stripMargin, "InlineFinalDriver")

    inlineChain = BenchmarkUtils.setupDriver(
      """object InlineChainDriver {
        |  object ChainHelper {
        |    @inline final def step1(x: Int): Int = x + 1
        |    @inline final def step2(x: Int): Int = step1(x) * 2
        |    @inline final def step3(x: Int): Int = step2(x) + step1(x)
        |    @inline final def compute(x: Int): Int = step3(x) + step2(x)
        |  }
        |  def run(): AnyRef = {
        |    var sum = 0
        |    var i = 0
        |    while (i < 10000) {
        |      sum += ChainHelper.compute(i)
        |      i += 1
        |    }
        |    Integer.valueOf(sum)
        |  }
        |}
      """.stripMargin, "InlineChainDriver")
  }

  @Benchmark def inlineFinalStock(bh: Blackhole): Unit = bh.consume(inlineFinal.stock())
  @Benchmark def inlineFinalGoron(bh: Blackhole): Unit = bh.consume(inlineFinal.goron())
  @Benchmark def inlineChainStock(bh: Blackhole): Unit = bh.consume(inlineChain.stock())
  @Benchmark def inlineChainGoron(bh: Blackhole): Unit = bh.consume(inlineChain.goron())
}
