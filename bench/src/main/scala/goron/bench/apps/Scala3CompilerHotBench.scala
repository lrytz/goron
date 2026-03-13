package goron.bench.apps

import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._

import java.io.File
import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

/** Hot benchmark for the Scala 3 compiler: classloaders are created once per trial
  * and reused across iterations. Each iteration invokes dotty.tools.dotc.Main.process
  * to compile source files.
  *
  * Run with: sbt "bench/Jmh/run Scala3CompilerHotBench"
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 10)
@Measurement(iterations = 10, time = 10)
@Fork(value = 3, jvmArgs = Array("-Xmx4g", "-Xms4g"))
class Scala3CompilerHotBench {

  @Param(Array("hello"))
  var sourceType: String = _

  private val scala3Version = "3.8.2"

  private var stockCl: URLClassLoader = _
  private var goronCl: URLClassLoader = _
  private var stockModule: AnyRef = _
  private var goronModule: AnyRef = _
  private var stockProcessMethod: java.lang.reflect.Method = _
  private var goronProcessMethod: java.lang.reflect.Method = _
  private var sourceFiles: Array[Path] = _
  private var sourceDir: Path = _
  private var outputDir: Path = _
  private var compilationClasspath: String = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    val compilerJars = BenchmarkUtils.resolve(s"org.scala-lang:scala3-compiler_3:$scala3Version")

    val optimizedJar = BenchmarkUtils.optimizeJars(
      compilerJars,
      List("dotty/tools/dotc/Main", "xsbti/UseScope", "xsbti/VirtualFile", "xsbti/compile/ExternalHooks")
    )

    stockCl = BenchmarkUtils.classLoaderFromJars(compilerJars)
    goronCl = BenchmarkUtils.classLoaderFromJars(Array(optimizedJar))

    val stockMainClass = stockCl.loadClass("dotty.tools.dotc.Main$")
    stockModule = stockMainClass.getField("MODULE$").get(null)
    stockProcessMethod = stockMainClass.getMethod("process", classOf[Array[String]])

    val goronMainClass = goronCl.loadClass("dotty.tools.dotc.Main$")
    goronModule = goronMainClass.getField("MODULE$").get(null)
    goronProcessMethod = goronMainClass.getMethod("process", classOf[Array[String]])

    val (files, dir, cp) = createSources(sourceType, compilerJars)
    sourceFiles = files
    sourceDir = dir
    compilationClasspath = cp

    outputDir = Files.createTempDirectory("scala3-bench-out")
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    ScalacBenchUtils.deleteRecursive(outputDir)
    if (sourceDir != null) ScalacBenchUtils.deleteRecursive(sourceDir)
    if (stockCl != null) stockCl.close()
    if (goronCl != null) goronCl.close()
  }

  @TearDown(Level.Invocation)
  def cleanOutput(): Unit = {
    ScalacBenchUtils.cleanOutputDir(outputDir)
  }

  @Benchmark
  def stock(): Unit = {
    val args = Array("-cp", compilationClasspath, "-d", outputDir.toString) ++ sourceFiles.map(_.toString)
    stockProcessMethod.invoke(stockModule, args)
  }

  @Benchmark
  def goron(): Unit = {
    val args = Array("-cp", compilationClasspath, "-d", outputDir.toString) ++ sourceFiles.map(_.toString)
    goronProcessMethod.invoke(goronModule, args)
  }

  private def createSources(sourceType: String, compilerJars: Array[File]): (Array[Path], Path, String) = {
    val cpJars = compilerJars.filter { f =>
      val name = f.getName
      name.contains("scala-library") || name.contains("scala3-library")
    }
    val cp = cpJars.map(_.getAbsolutePath).mkString(File.pathSeparator)

    sourceType match {
      case "hello" =>
        val dir = Files.createTempDirectory("scala3-bench-src")
        val file = dir.resolve("Hello.scala")
        Files.writeString(
          file,
          """@main def hello(): Unit =
            |  println("Hello, World!")
            |  val xs = (1 to 100).map(_ * 2).filter(_ > 50)
            |  println(xs.sum)
            |""".stripMargin
        )
        (Array(file), dir, cp)

      case other =>
        throw new IllegalArgumentException(s"Unknown source type: $other")
    }
  }
}
