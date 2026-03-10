package goron.bench

import goron._
import org.openjdk.jmh.annotations._

import java.io.File
import java.net.{URL, URLClassLoader}
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import scala.jdk.CollectionConverters._

/** Benchmark comparing Scala compiler performance: stock scalac 2.13.18 vs goron-optimized scalac.
  *
  * Each iteration compiles source files using the compiler loaded from the respective jar. The benchmark measures
  * wall-clock compilation time, which captures the combined effect of inlining, dead code elimination, and reduced
  * class loading.
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

  /** Source to compile: "hello" for a small file, "scalap" for the scalap module (~30 files) */
  @Param(Array("hello", "scalap"))
  var sourceType: String = _

  private var stockScalacJars: Array[File] = _
  private var optimizedJar: File = _
  private var sourceFiles: Array[Path] = _
  private var sourceDir: Path = _
  private var outputDir: Path = _
  private var compilationClasspath: String = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    // Find scala-compiler and scala-library jars from the classpath
    stockScalacJars = findScalacJars()

    // Build the optimized compiler
    optimizedJar = buildOptimizedCompiler(stockScalacJars)

    // Create source files and determine compilation classpath
    val (files, dir, cp) = createSources(sourceType)
    sourceFiles = files
    sourceDir = dir
    compilationClasspath = cp

    // Create output directory
    outputDir = Files.createTempDirectory("scalac-bench-out")
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    deleteRecursive(outputDir)
    if (sourceDir != null) deleteRecursive(sourceDir)
    if (optimizedJar != null) optimizedJar.delete()
  }

  @TearDown(Level.Invocation)
  def cleanOutput(): Unit = {
    // Clean output directory between invocations
    if (outputDir != null && Files.exists(outputDir)) {
      Files
        .walk(outputDir)
        .sorted(java.util.Comparator.reverseOrder())
        .filter(p => p != outputDir)
        .forEach(p => Files.deleteIfExists(p))
    }
  }

  @Benchmark
  def stockScalac(): Unit = {
    runScalac(stockScalacJars.map(_.toURI.toURL), sourceFiles, compilationClasspath, outputDir)
  }

  @Benchmark
  def goronScalac(): Unit = {
    runScalac(Array(optimizedJar.toURI.toURL), sourceFiles, compilationClasspath, outputDir)
  }

  /** Run scalac in an isolated classloader */
  private def runScalac(
      urls: Array[URL],
      sources: Array[Path],
      cp: String,
      outDir: Path
  ): Unit = {
    val cl = new URLClassLoader(urls, ClassLoader.getSystemClassLoader.getParent)
    try {
      val mainClass = cl.loadClass("scala.tools.nsc.Main")
      val processMethod = mainClass.getMethod("process", classOf[Array[String]])

      val args = Array("-cp", cp, "-d", outDir.toString) ++ sources.map(_.toString)
      processMethod.invoke(null, args)
    } finally {
      cl.close()
    }
  }

  private def findScalacJars(): Array[File] = {
    val cp = System.getProperty("java.class.path", "")
    val entries = cp.split(File.pathSeparator).map(new File(_))

    val compiler = entries
      .find(f => f.getName.contains("scala-compiler") && f.getName.endsWith(".jar"))
      .getOrElse(throw new RuntimeException("scala-compiler not found on classpath"))
    val library = entries
      .find(f => f.getName.contains("scala-library") && f.getName.endsWith(".jar"))
      .getOrElse(throw new RuntimeException("scala-library not found on classpath"))
    val reflect = entries
      .find(f => f.getName.contains("scala-reflect") && f.getName.endsWith(".jar"))
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

  /** Create source files and return (sourceFiles, sourceDir, compilationClasspath) */
  private def createSources(sourceType: String): (Array[Path], Path, String) = {
    val scalaLibrary = stockScalacJars
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
        val sourcesJar = downloadScalapSources(dir)
        val files = extractScalaSources(sourcesJar, dir)
        sourcesJar.delete()

        // scalap depends on scala-library, scala-reflect, and scala-compiler
        val cp = stockScalacJars.map(_.getAbsolutePath).mkString(File.pathSeparator)

        (files, dir, cp)

      case other =>
        throw new IllegalArgumentException(s"Unknown source type: $other")
    }
  }

  /** Download scalap-2.13.18-sources.jar from Maven Central */
  private def downloadScalapSources(targetDir: Path): File = {
    val url = new URL(
      "https://repo1.maven.org/maven2/org/scala-lang/scalap/2.13.18/scalap-2.13.18-sources.jar"
    )
    val targetFile = targetDir.resolve("scalap-sources.jar").toFile
    val conn = url.openConnection()
    val in = conn.getInputStream
    try {
      Files.copy(in, targetFile.toPath)
    } finally {
      in.close()
    }
    targetFile
  }

  /** Extract .scala files from a sources jar */
  private def extractScalaSources(sourcesJar: File, targetDir: Path): Array[Path] = {
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
