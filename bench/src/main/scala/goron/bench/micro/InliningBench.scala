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

  private var stockLoader: ClassLoader = _
  private var optimizedLoader: ClassLoader = _

  private val inlineFinalCode =
    """object MathHelper {
      |  @inline final def square(x: Int): Int = x * x
      |  @inline final def cube(x: Int): Int = x * x * x
      |  @inline final def add(a: Int, b: Int): Int = a + b
      |}
      |
      |object InlineFinalRunner {
      |  def run(n: Int): Int = {
      |    var sum = 0
      |    var i = 0
      |    while (i < n) {
      |      sum = MathHelper.add(sum, MathHelper.square(i))
      |      sum = MathHelper.add(sum, MathHelper.cube(i % 10))
      |      i += 1
      |    }
      |    sum
      |  }
      |}
      |""".stripMargin

  private val inlineChainCode =
    """object ChainHelper {
      |  @inline final def step1(x: Int): Int = x + 1
      |  @inline final def step2(x: Int): Int = step1(x) * 2
      |  @inline final def step3(x: Int): Int = step2(x) + step1(x)
      |  @inline final def compute(x: Int): Int = step3(x) + step2(x)
      |}
      |
      |object InlineChainRunner {
      |  def run(n: Int): Int = {
      |    var sum = 0
      |    var i = 0
      |    while (i < n) {
      |      sum += ChainHelper.compute(i)
      |      i += 1
      |    }
      |    sum
      |  }
      |}
      |""".stripMargin

  @Param(Array("inlineFinal", "inlineChain"))
  var variant: String = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    val code = variant match {
      case "inlineFinal" => inlineFinalCode
      case "inlineChain" => inlineChainCode
    }
    val (stock, optimized) = BenchmarkUtils.compileAndOptimize(code)
    stockLoader = BenchmarkUtils.classLoaderFromBytes(stock)
    optimizedLoader = BenchmarkUtils.classLoaderFromBytes(optimized)
  }

  @Benchmark
  def stock(bh: Blackhole): Unit = {
    val runner = variant match {
      case "inlineFinal" => "InlineFinalRunner"
      case "inlineChain" => "InlineChainRunner"
    }
    val cls = stockLoader.loadClass(runner)
    val method = cls.getMethod("run", classOf[Int])
    bh.consume(method.invoke(null, Integer.valueOf(10000)))
  }

  @Benchmark
  def goron(bh: Blackhole): Unit = {
    val runner = variant match {
      case "inlineFinal" => "InlineFinalRunner"
      case "inlineChain" => "InlineChainRunner"
    }
    val cls = optimizedLoader.loadClass(runner)
    val method = cls.getMethod("run", classOf[Int])
    bh.consume(method.invoke(null, Integer.valueOf(10000)))
  }
}
