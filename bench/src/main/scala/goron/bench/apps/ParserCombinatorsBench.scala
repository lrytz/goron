package goron.bench.apps

import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Benchmark for scala-parser-combinators library optimization.
  *
  * Parser combinators allocate closures heavily for parser composition.
  * The driver exercises actual parsing with ~, |, ^^, and rep.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 10, time = 5)
@Fork(value = 1, jvmArgs = Array("-Xmx4g", "-Xms4g"))
class ParserCombinatorsBench {

  private var driver: BenchmarkUtils.DriverSetup = _

  private val driverCode =
    """import scala.util.parsing.combinator._
      |
      |object PCDriver extends RegexParsers {
      |  def number: Parser[Int] = "\\d+".r ^^ (_.toInt)
      |  def factor: Parser[Int] = number | "(" ~> expr <~ ")"
      |  def term: Parser[Int] = factor ~ rep("*" ~> factor) ^^ {
      |    case f ~ fs => fs.foldLeft(f)(_ * _)
      |  }
      |  def expr: Parser[Int] = term ~ rep("+" ~> term) ^^ {
      |    case t ~ ts => ts.foldLeft(t)(_ + _)
      |  }
      |
      |  def run(): AnyRef = {
      |    var result: AnyRef = null
      |    val inputs = Array("1+2*3", "(1+2)*3", "1+2+3+4+5", "((1+2)*(3+4))+5", "1*2*3*4*5+6+7+8")
      |    var i = 0
      |    while (i < 2000) {
      |      var j = 0
      |      while (j < inputs.length) {
      |        result = parseAll(expr, inputs(j)).asInstanceOf[AnyRef]
      |        j += 1
      |      }
      |      i += 1
      |    }
      |    result
      |  }
      |}
    """.stripMargin

  @Setup(Level.Trial)
  def setup(): Unit = {
    driver = BenchmarkUtils.setupDriver(driverCode, "PCDriver",
      "org.scala-lang.modules:scala-parser-combinators_2.13:2.4.0")
  }

  @Benchmark
  def stock(bh: Blackhole): Unit = bh.consume(driver.stock())

  @Benchmark
  def goron(bh: Blackhole): Unit = bh.consume(driver.goron())
}
