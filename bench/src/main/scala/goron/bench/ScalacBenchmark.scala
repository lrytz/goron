package goron.bench

import goron._
import goron.optimizer.opt.InlineInfoAttributePrototype

import java.io.File
import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import org.openjdk.jmh.annotations._
import scala.tools.asm

/** Benchmark comparing Scala compiler performance: stock scalac 2.13.18 vs goron-optimized scalac.
  *
  * Each iteration compiles a small Scala source file using the compiler loaded from the respective jar. The benchmark
  * measures wall-clock compilation time, which captures the combined effect of inlining, dead code elimination, and
  * reduced class loading.
  *
  * Run with: sbt "bench/Jmh/run ScalacBenchmark"
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 10)
@Measurement(iterations = 10, time = 10)
@Fork(value = 1, jvmArgs = Array("-Xmx4g", "-Xms4g"))
class ScalacBenchmark {

  /** Source code to compile in each iteration */
  @Param(Array("hello"))
  var sourceType: String = _

  private var stockScalacJars: Array[File] = _
  private var optimizedJar: File = _
  private var sourceFile: Path = _
  private var outputDir: Path = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    // Find scala-compiler and scala-library jars from the classpath
    stockScalacJars = findScalacJars()

    // Build the optimized compiler
    optimizedJar = buildOptimizedCompiler(stockScalacJars)

    // Create the source file to compile
    sourceFile = createSourceFile(sourceType)

    // Create output directory
    outputDir = Files.createTempDirectory("scalac-bench-out")
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    deleteRecursive(outputDir)
    deleteRecursive(sourceFile)
    if (optimizedJar != null) optimizedJar.delete()
  }

  @TearDown(Level.Invocation)
  def cleanOutput(): Unit = {
    // Clean output directory between invocations
    if (outputDir != null && Files.exists(outputDir)) {
      Files.walk(outputDir).sorted(java.util.Comparator.reverseOrder())
        .filter(p => p != outputDir)
        .forEach(p => Files.deleteIfExists(p))
    }
  }

  @Benchmark
  def stockScalac(): Unit = {
    runScalac(stockScalacJars.map(_.toURI.toURL), sourceFile, outputDir)
  }

  @Benchmark
  def goronScalac(): Unit = {
    runScalac(Array(optimizedJar.toURI.toURL), sourceFile, outputDir)
  }

  /** Run scalac in an isolated classloader */
  private def runScalac(urls: Array[java.net.URL], source: Path, outDir: Path): Unit = {
    val cl = new URLClassLoader(urls, ClassLoader.getSystemClassLoader.getParent)
    try {
      val mainClass = cl.loadClass("scala.tools.nsc.Main")
      val processMethod = mainClass.getMethod("process", classOf[Array[String]])

      // Find scala-library for the compilation classpath
      val libUrl = urls.find(_.getPath.contains("scala-library"))
        .orElse(stockScalacJars.find(_.getName.contains("scala-library")).map(_.toURI.toURL))
        .getOrElse(throw new RuntimeException("Cannot find scala-library"))

      val args = Array(
        "-cp", new File(libUrl.toURI).getAbsolutePath,
        "-d", outDir.toString,
        source.toString
      )

      processMethod.invoke(null, args)
    } finally {
      cl.close()
    }
  }

  private def findScalacJars(): Array[File] = {
    val cp = System.getProperty("java.class.path", "")
    val entries = cp.split(File.pathSeparator).map(new File(_))

    val compiler = entries.find(f => f.getName.contains("scala-compiler") && f.getName.endsWith(".jar"))
      .getOrElse(throw new RuntimeException("scala-compiler not found on classpath"))
    val library = entries.find(f => f.getName.contains("scala-library") && f.getName.endsWith(".jar"))
      .getOrElse(throw new RuntimeException("scala-library not found on classpath"))
    val reflect = entries.find(f => f.getName.contains("scala-reflect") && f.getName.endsWith(".jar"))
      .getOrElse(throw new RuntimeException("scala-reflect not found on classpath"))

    // Also need jline and jna for the compiler
    val extras = entries.filter { f =>
      val name = f.getName
      name.endsWith(".jar") && (name.contains("jline") || name.contains("jna"))
    }

    Array(compiler, library, reflect) ++ extras
  }

  private def buildOptimizedCompiler(jars: Array[File]): File = {
    val outputJar = File.createTempFile("scalac-optimized-", ".jar")

    val config = GoronConfig(
      inputJars = jars.map(_.getAbsolutePath).toList,
      outputJar = outputJar.getAbsolutePath,
      entryPoints = List("scala/tools/nsc/Main", "scala/tools/nsc/reporters/ConsoleReporter"),
      verbose = false
    )

    Goron.run(config)
    outputJar
  }

  private def createSourceFile(sourceType: String): Path = {
    val content = sourceType match {
      case "hello" =>
        """object Hello {
          |  def main(args: Array[String]): Unit = {
          |    println("Hello, World!")
          |    val xs = (1 to 100).map(_ * 2).filter(_ > 50)
          |    println(xs.sum)
          |  }
          |}
          |""".stripMargin
      case other =>
        throw new IllegalArgumentException(s"Unknown source type: $other")
    }
    val file = Files.createTempFile("scalac-bench-", ".scala")
    Files.writeString(file, content)
    file
  }

  private def deleteRecursive(path: Path): Unit = {
    if (path != null && Files.exists(path)) {
      if (Files.isDirectory(path)) {
        Files.walk(path).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
      } else {
        Files.deleteIfExists(path)
      }
    }
  }
}
