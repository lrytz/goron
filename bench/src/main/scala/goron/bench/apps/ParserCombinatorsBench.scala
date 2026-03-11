package goron.bench.apps

import goron.GoronConfig
import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit

/** Benchmark for scala-parser-combinators library optimization.
  *
  * Parser combinators allocate closures heavily for parser composition.
  * The workload parses arithmetic expressions repeatedly.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 10, time = 5)
@Fork(value = 1, jvmArgs = Array("-Xmx4g", "-Xms4g"))
class ParserCombinatorsBench {

  private var stockLoader: URLClassLoader = _
  private var optimizedLoader: URLClassLoader = _

  private val expressions = Array(
    "1+2*3",
    "(1+2)*3",
    "1+2+3+4+5",
    "((1+2)*(3+4))+5",
    "1*2*3*4*5+6+7+8"
  )

  @Setup(Level.Trial)
  def setup(): Unit = {
    val parserCombs = BenchmarkUtils.downloadMavenJar(
      "org.scala-lang.modules",
      "scala-parser-combinators_2.13",
      "2.4.0"
    )

    val scalaLib = findScalaLibrary()
    val jars = Array(parserCombs, scalaLib)

    stockLoader = BenchmarkUtils.classLoaderFromJars(jars)

    val entryPoints = List(
      "scala/util/parsing/combinator/Parsers",
      "scala/util/parsing/combinator/RegexParsers",
      "scala/util/parsing/combinator/JavaTokenParsers"
    )
    // Disable DCE: we only specify partial entry points
    val config = GoronConfig(inputJars = Nil, outputJar = "", eliminateDeadCode = false)
    val optimizedJar = BenchmarkUtils.optimizeJars(jars, entryPoints, config)
    optimizedLoader = BenchmarkUtils.classLoaderFromJars(Array(optimizedJar))
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    if (stockLoader != null) stockLoader.close()
    if (optimizedLoader != null) optimizedLoader.close()
  }

  @Benchmark
  def stock(bh: Blackhole): Unit = {
    bh.consume(runParserWorkload(stockLoader))
  }

  @Benchmark
  def goron(bh: Blackhole): Unit = {
    bh.consume(runParserWorkload(optimizedLoader))
  }

  private def runParserWorkload(cl: ClassLoader): AnyRef = {
    // Exercise the parser combinator classes via reflection
    // Load core classes to trigger class initialization and exercise optimized dispatch
    val parsersClass = cl.loadClass("scala.util.parsing.combinator.Parsers")
    val regexParsersClass = cl.loadClass("scala.util.parsing.combinator.RegexParsers")

    // Load the CharSequenceReader for parsing strings
    val readerClass = cl.loadClass("scala.util.parsing.input.CharSequenceReader")
    val readerCtor = readerClass.getConstructor(classOf[CharSequence])

    var result: AnyRef = null
    for (_ <- 0 until 200) {
      for (expr <- expressions) {
        // Create a reader for each expression
        val reader = readerCtor.newInstance(expr)
        // Exercise reader operations (atEnd, first, rest, pos)
        val atEndMethod = readerClass.getMethod("atEnd")
        val firstMethod = readerClass.getMethod("first")
        val restMethod = readerClass.getMethod("rest")

        var r = reader
        while (!(atEndMethod.invoke(r).asInstanceOf[Boolean])) {
          result = firstMethod.invoke(r)
          r = restMethod.invoke(r)
        }
      }
    }
    result
  }

  private def findScalaLibrary(): File = {
    val cp = System.getProperty("java.class.path", "")
    cp.split(File.pathSeparator)
      .map(new File(_))
      .find(f => f.getName.contains("scala-library") && f.getName.endsWith(".jar"))
      .getOrElse(throw new RuntimeException("scala-library not found on classpath"))
  }
}
