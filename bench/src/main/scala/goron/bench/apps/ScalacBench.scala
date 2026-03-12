package goron.bench.apps

import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._

import java.io.File
import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import scala.jdk.CollectionConverters._

/** Hot benchmark: classloaders are created once per trial and reused across iterations.
  * Each iteration creates a fresh compiler (new Global via Main.process), but benefits
  * from JIT optimizations accumulated in prior iterations — matching how a long-running
  * build server would behave.
  *
  * Run with: sbt "bench/Jmh/run ScalacBench"
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 10)
@Measurement(iterations = 10, time = 10)
@Fork(value = 3, jvmArgs = Array("-Xmx4g", "-Xms4g"))
class ScalacBench {

  @Param(Array("hello", "scalap"))
  var sourceType: String = _

  private val scalaVersion = "2.13.18"

  private var stockCl: URLClassLoader = _
  private var goronCl: URLClassLoader = _
  private var stockProcessMethod: java.lang.reflect.Method = _
  private var goronProcessMethod: java.lang.reflect.Method = _
  private var sourceFiles: Array[Path] = _
  private var sourceDir: Path = _
  private var outputDir: Path = _
  private var compilationClasspath: String = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    val compilerJars = BenchmarkUtils.resolve(s"org.scala-lang:scala-compiler:$scalaVersion")

    val optimizedJar = BenchmarkUtils.optimizeJars(
      compilerJars,
      List("scala/tools/nsc/Main", "scala/tools/nsc/reporters/ConsoleReporter")
    )

    stockCl = BenchmarkUtils.classLoaderFromJars(compilerJars)
    goronCl = BenchmarkUtils.classLoaderFromJars(Array(optimizedJar))
    stockProcessMethod = stockCl.loadClass("scala.tools.nsc.Main").getMethod("process", classOf[Array[String]])
    goronProcessMethod = goronCl.loadClass("scala.tools.nsc.Main").getMethod("process", classOf[Array[String]])

    val (files, dir, cp) = ScalacBenchUtils.createSources(sourceType, compilerJars, scalaVersion)
    sourceFiles = files
    sourceDir = dir
    compilationClasspath = cp

    outputDir = Files.createTempDirectory("scalac-bench-out")
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
    stockProcessMethod.invoke(null, args)
  }

  @Benchmark
  def goron(): Unit = {
    val args = Array("-cp", compilationClasspath, "-d", outputDir.toString) ++ sourceFiles.map(_.toString)
    goronProcessMethod.invoke(null, args)
  }
}

/** Cold benchmark: a fresh classloader is created for each invocation.
  * Measures one-shot compilation cost including class loading overhead.
  *
  * Run with: sbt "bench/Jmh/run ScalacColdBench"
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 10)
@Measurement(iterations = 10, time = 10)
@Fork(value = 1, jvmArgs = Array("-Xmx4g", "-Xms4g"))
class ScalacColdBench {

  @Param(Array("hello", "scalap"))
  var sourceType: String = _

  private val scalaVersion = "2.13.18"

  private var compilerJars: Array[File] = _
  private var optimizedJar: File = _
  private var sourceFiles: Array[Path] = _
  private var sourceDir: Path = _
  private var outputDir: Path = _
  private var compilationClasspath: String = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    compilerJars = BenchmarkUtils.resolve(s"org.scala-lang:scala-compiler:$scalaVersion")

    optimizedJar = BenchmarkUtils.optimizeJars(
      compilerJars,
      List("scala/tools/nsc/Main", "scala/tools/nsc/reporters/ConsoleReporter")
    )

    val (files, dir, cp) = ScalacBenchUtils.createSources(sourceType, compilerJars, scalaVersion)
    sourceFiles = files
    sourceDir = dir
    compilationClasspath = cp

    outputDir = Files.createTempDirectory("scalac-bench-out")
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    ScalacBenchUtils.deleteRecursive(outputDir)
    if (sourceDir != null) ScalacBenchUtils.deleteRecursive(sourceDir)
  }

  @TearDown(Level.Invocation)
  def cleanOutput(): Unit = {
    ScalacBenchUtils.cleanOutputDir(outputDir)
  }

  @Benchmark
  def stock(): Unit = {
    runScalac(compilerJars, sourceFiles, compilationClasspath, outputDir)
  }

  @Benchmark
  def goron(): Unit = {
    runScalac(Array(optimizedJar), sourceFiles, compilationClasspath, outputDir)
  }

  private def runScalac(
      jars: Array[File],
      sources: Array[Path],
      cp: String,
      outDir: Path
  ): Unit = {
    val cl = BenchmarkUtils.classLoaderFromJars(jars)
    try {
      val mainClass = cl.loadClass("scala.tools.nsc.Main")
      val processMethod = mainClass.getMethod("process", classOf[Array[String]])
      val args = Array("-cp", cp, "-d", outDir.toString) ++ sources.map(_.toString)
      processMethod.invoke(null, args)
    } finally {
      cl.close()
    }
  }
}

private[apps] object ScalacBenchUtils {

  def createSources(sourceType: String, compilerJars: Array[File], scalaVersion: String): (Array[Path], Path, String) = {
    val scalaLibrary = compilerJars
      .find(_.getName.contains("scala-library"))
      .getOrElse(throw new RuntimeException("scala-library not found"))

    sourceType match {
      case "hello" =>
        val dir = Files.createTempDirectory("scalac-bench-src")
        val file = dir.resolve("Hello.scala")
        Files.writeString(
          file,
          """object Hello {
            |  def main(args: Array[String]): Unit = {
            |    println("Hello, World!")
            |    val xs = (1 to 100).map(_ * 2).filter(_ > 50)
            |    println(xs.sum)
            |  }
            |}
            |""".stripMargin
        )
        (Array(file), dir, scalaLibrary.getAbsolutePath)

      case "scalap" =>
        val dir = Files.createTempDirectory("scalac-bench-scalap")
        val sourcesJar = resolveScalapSources(scalaVersion)
        val files = extractScalaSources(sourcesJar, dir)
        val cp = compilerJars.map(_.getAbsolutePath).mkString(File.pathSeparator)
        (files, dir, cp)

      case other =>
        throw new IllegalArgumentException(s"Unknown source type: $other")
    }
  }

  def resolveScalapSources(scalaVersion: String): File = {
    import coursier._
    val dep = Dependency(Module(Organization("org.scala-lang"), ModuleName("scalap")), scalaVersion)
      .withTransitive(false)
    val files = Fetch()
      .addDependencies(dep)
      .addClassifiers(Classifier.sources)
      .run()
    files.headOption.getOrElse(
      throw new RuntimeException(s"Could not resolve scalap $scalaVersion sources jar")
    )
  }

  def extractScalaSources(sourcesJar: File, targetDir: Path): Array[Path] = {
    val jar = new JarFile(sourcesJar)
    try {
      val files = scala.collection.mutable.ArrayBuffer.empty[Path]
      jar.entries().asScala.foreach { entry =>
        if (!entry.isDirectory && entry.getName.endsWith(".scala")) {
          val target = targetDir.resolve(entry.getName)
          Files.createDirectories(target.getParent)
          val in = jar.getInputStream(entry)
          try {
            Files.copy(in, target)
          } finally {
            in.close()
          }
          files += target
        }
      }
      files.toArray
    } finally {
      jar.close()
    }
  }

  def deleteRecursive(path: Path): Unit = {
    if (path != null && Files.exists(path)) {
      if (Files.isDirectory(path)) {
        Files.walk(path).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
      } else {
        Files.deleteIfExists(path)
      }
    }
  }

  def cleanOutputDir(outputDir: Path): Unit = {
    if (outputDir != null && Files.exists(outputDir)) {
      Files
        .walk(outputDir)
        .sorted(java.util.Comparator.reverseOrder())
        .filter(p => p != outputDir)
        .forEach(p => Files.deleteIfExists(p))
    }
  }
}
