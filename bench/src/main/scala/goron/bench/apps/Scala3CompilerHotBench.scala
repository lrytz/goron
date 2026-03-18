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
  *   -p sourceType=hello      (default, tiny program)
  *   -p sourceType=scalaYaml  (7.7K LoC YAML parser, no external deps)
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
  private var compilerArgs: Array[String] = _

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

    val sourceSetup = Scala3BenchSources.create(sourceType, compilerJars, scala3Version)
    sourceFiles = sourceSetup.files
    sourceDir = sourceSetup.tmpDir
    compilerArgs = sourceSetup.compilerArgs

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
    val args = compilerArgs ++ Array("-d", outputDir.toString) ++ sourceFiles.map(_.toString)
    stockProcessMethod.invoke(stockModule, args)
  }

  @Benchmark
  def goron(): Unit = {
    val args = compilerArgs ++ Array("-d", outputDir.toString) ++ sourceFiles.map(_.toString)
    goronProcessMethod.invoke(goronModule, args)
  }
}
