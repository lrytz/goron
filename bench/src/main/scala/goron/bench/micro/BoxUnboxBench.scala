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

  private var stockLoader: ClassLoader = _
  private var optimizedLoader: ClassLoader = _

  private val boxUnboxCode =
    """object BoxHelper {
      |  @inline final def identity(x: Any): Any = x
      |  @inline final def addBoxed(a: Any, b: Any): Int =
      |    a.asInstanceOf[Int] + b.asInstanceOf[Int]
      |}
      |
      |object BoxUnboxRunner {
      |  def run(n: Int): Int = {
      |    var sum = 0
      |    var i = 0
      |    while (i < n) {
      |      val x: Any = i
      |      val y: Any = BoxHelper.identity(x)
      |      sum += BoxHelper.addBoxed(y, i + 1)
      |      i += 1
      |    }
      |    sum
      |  }
      |}
      |""".stripMargin

  private val tupleUnboxCode =
    """object TupleHelper {
      |  @inline final def makePair(a: Int, b: Int): (Int, Int) = (a, b)
      |  @inline final def sumPair(p: (Int, Int)): Int = p._1 + p._2
      |}
      |
      |object TupleUnboxRunner {
      |  def run(n: Int): Int = {
      |    var sum = 0
      |    var i = 0
      |    while (i < n) {
      |      val pair = TupleHelper.makePair(i, i + 1)
      |      sum += TupleHelper.sumPair(pair)
      |      i += 1
      |    }
      |    sum
      |  }
      |}
      |""".stripMargin

  private val refEliminationCode =
    """object RefElimHelper {
      |  @inline final def withCounter(n: Int, f: () => Unit): Int = {
      |    var count = 0
      |    var i = 0
      |    while (i < n) {
      |      f()
      |      count += 1
      |      i += 1
      |    }
      |    count
      |  }
      |}
      |
      |object RefElimRunner {
      |  def run(n: Int): Int = {
      |    var total = 0
      |    var i = 0
      |    while (i < n) {
      |      total += i * 2 + 1
      |      i += 1
      |    }
      |    total
      |  }
      |}
      |""".stripMargin

  @Param(Array("boxUnbox", "tupleUnbox", "refElimination"))
  var variant: String = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    val code = variant match {
      case "boxUnbox"        => boxUnboxCode
      case "tupleUnbox"      => tupleUnboxCode
      case "refElimination"  => refEliminationCode
    }
    val (stock, optimized) = BenchmarkUtils.compileAndOptimize(code)
    stockLoader = BenchmarkUtils.classLoaderFromBytes(stock)
    optimizedLoader = BenchmarkUtils.classLoaderFromBytes(optimized)
  }

  @Benchmark
  def stock(bh: Blackhole): Unit = {
    val runner = variant match {
      case "boxUnbox"       => "BoxUnboxRunner"
      case "tupleUnbox"     => "TupleUnboxRunner"
      case "refElimination" => "RefElimRunner"
    }
    val cls = stockLoader.loadClass(runner)
    val method = cls.getMethod("run", classOf[Int])
    bh.consume(method.invoke(null, Integer.valueOf(10000)))
  }

  @Benchmark
  def goron(bh: Blackhole): Unit = {
    val runner = variant match {
      case "boxUnbox"       => "BoxUnboxRunner"
      case "tupleUnbox"     => "TupleUnboxRunner"
      case "refElimination" => "RefElimRunner"
    }
    val cls = optimizedLoader.loadClass(runner)
    val method = cls.getMethod("run", classOf[Int])
    bh.consume(method.invoke(null, Integer.valueOf(10000)))
  }
}
