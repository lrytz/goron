package goron.bench.micro

import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Benchmark for box/unbox elimination optimizations.
  *
  * Tests elimination of boxing round-trips through Any, tuple creation/destructuring,
  * and IntRef elimination for captured vars.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(value = 2, jvmArgs = Array("-Xmx2g"))
class BoxUnboxBench {

  private var boxUnbox: BenchmarkUtils.DriverSetup = _
  private var tupleUnbox: BenchmarkUtils.DriverSetup = _
  private var refElim: BenchmarkUtils.DriverSetup = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    boxUnbox = BenchmarkUtils.setupDriver(
      """object BoxUnboxDriver {
        |  object BoxHelper {
        |    @inline final def identity(x: Any): Any = x
        |    @inline final def addBoxed(a: Any, b: Any): Int =
        |      a.asInstanceOf[Int] + b.asInstanceOf[Int]
        |  }
        |  def run(): AnyRef = {
        |    var sum = 0
        |    var i = 0
        |    while (i < 10000) {
        |      val x: Any = i
        |      val y: Any = BoxHelper.identity(x)
        |      sum += BoxHelper.addBoxed(y, i + 1)
        |      i += 1
        |    }
        |    Integer.valueOf(sum)
        |  }
        |}
      """.stripMargin, "BoxUnboxDriver")

    tupleUnbox = BenchmarkUtils.setupDriver(
      """object TupleUnboxDriver {
        |  object TupleHelper {
        |    @inline final def makePair(a: Int, b: Int): (Int, Int) = (a, b)
        |    @inline final def sumPair(p: (Int, Int)): Int = p._1 + p._2
        |  }
        |  def run(): AnyRef = {
        |    var sum = 0
        |    var i = 0
        |    while (i < 10000) {
        |      val pair = TupleHelper.makePair(i, i + 1)
        |      sum += TupleHelper.sumPair(pair)
        |      i += 1
        |    }
        |    Integer.valueOf(sum)
        |  }
        |}
      """.stripMargin, "TupleUnboxDriver")

    refElim = BenchmarkUtils.setupDriver(
      """object RefElimDriver {
        |  def run(): AnyRef = {
        |    var total = 0
        |    var i = 0
        |    while (i < 10000) {
        |      total += i * 2 + 1
        |      i += 1
        |    }
        |    Integer.valueOf(total)
        |  }
        |}
      """.stripMargin, "RefElimDriver")
  }

  @Benchmark def stockBoxUnbox(bh: Blackhole): Unit = bh.consume(boxUnbox.stock())
  @Benchmark def goronBoxUnbox(bh: Blackhole): Unit = bh.consume(boxUnbox.goron())
  @Benchmark def stockTupleUnbox(bh: Blackhole): Unit = bh.consume(tupleUnbox.stock())
  @Benchmark def goronTupleUnbox(bh: Blackhole): Unit = bh.consume(tupleUnbox.goron())
  @Benchmark def stockRefElimination(bh: Blackhole): Unit = bh.consume(refElim.stock())
  @Benchmark def goronRefElimination(bh: Blackhole): Unit = bh.consume(refElim.goron())
}
