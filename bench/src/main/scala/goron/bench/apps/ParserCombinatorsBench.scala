package goron.bench.apps

import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit

/** Benchmark for scala-parser-combinators library optimization.
  *
  * Parser combinators allocate closures heavily for parser composition.
  * The workload walks through input strings using CharSequenceReader, exercising
  * the core reader dispatch and position tracking.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 10, time = 5)
@Fork(value = 1, jvmArgs = Array("-Xmx4g", "-Xms4g"))
class ParserCombinatorsBench {

  private var stockLoader: URLClassLoader = _
  private var optimizedLoader: URLClassLoader = _

  // Cached reflection handles
  private var stockReader: ReaderHandles = _
  private var goronReader: ReaderHandles = _

  private class ReaderHandles(
      val readerCtor: java.lang.reflect.Constructor[_],
      val atEndMethod: java.lang.reflect.Method,
      val firstMethod: java.lang.reflect.Method,
      val restMethod: java.lang.reflect.Method,
      val posMethod: java.lang.reflect.Method,
      val dropMethod: java.lang.reflect.Method
  )

  private val inputText =
    "The quick brown fox jumps over the lazy dog. " * 20

  @Setup(Level.Trial)
  def setup(): Unit = {
    val jars = BenchmarkUtils.resolve("org.scala-lang.modules:scala-parser-combinators_2.13:2.4.0")

    stockLoader = BenchmarkUtils.classLoaderFromJars(jars)

    val entryPoints = List(
      "scala/util/parsing/combinator/Parsers",
      "scala/util/parsing/combinator/RegexParsers",
      "scala/util/parsing/combinator/JavaTokenParsers",
      "scala/util/parsing/input/CharSequenceReader"
    )
    val optimizedJar = BenchmarkUtils.optimizeJars(jars, entryPoints)
    optimizedLoader = BenchmarkUtils.classLoaderFromJars(Array(optimizedJar))

    stockReader = setupHandles(stockLoader)
    goronReader = setupHandles(optimizedLoader)
  }

  private def setupHandles(cl: ClassLoader): ReaderHandles = {
    val readerClass = cl.loadClass("scala.util.parsing.input.CharSequenceReader")
    val readerCtor = readerClass.getConstructor(classOf[CharSequence])
    val atEndMethod = readerClass.getMethod("atEnd")
    val firstMethod = readerClass.getMethod("first")
    val restMethod = readerClass.getMethod("rest")
    val posMethod = readerClass.getMethod("pos")
    val dropMethod = readerClass.getMethods.find(m => m.getName == "drop" && m.getParameterCount == 1).orNull
    new ReaderHandles(readerCtor, atEndMethod, firstMethod, restMethod, posMethod, dropMethod)
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    if (stockLoader != null) stockLoader.close()
    if (optimizedLoader != null) optimizedLoader.close()
  }

  @Benchmark
  def stock(bh: Blackhole): Unit = {
    bh.consume(runWorkload(stockReader))
  }

  @Benchmark
  def goron(bh: Blackhole): Unit = {
    bh.consume(runWorkload(goronReader))
  }

  private def runWorkload(h: ReaderHandles): AnyRef = {
    var result: AnyRef = null

    for (_ <- 0 until 500) {
      var r: AnyRef = h.readerCtor.newInstance(inputText).asInstanceOf[AnyRef]

      // Walk through the entire input, exercising first/rest dispatch
      while (!(h.atEndMethod.invoke(r).asInstanceOf[Boolean])) {
        h.firstMethod.invoke(r)
        h.posMethod.invoke(r)
        r = h.restMethod.invoke(r)
      }
      result = r
    }
    result
  }
}
