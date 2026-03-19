package goron.bench.apps

import java.io.File
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._
import scala.sys.process._

/** Source setup for Scala 3 compiler benchmarks. */
case class Scala3BenchSources(
    files: Array[Path],
    tmpDir: Path,
    compilerArgs: Array[String]
)

object Scala3BenchSources {

  def create(sourceType: String, compilerJars: Array[File], scala3Version: String): Scala3BenchSources = {
    sourceType match {
      case "hello"     => createHello(compilerJars)
      case "scalaYaml" => createScalaYaml(compilerJars)
      case other       => throw new IllegalArgumentException(s"Unknown source type: $other")
    }
  }

  private def createHello(compilerJars: Array[File]): Scala3BenchSources = {
    val cp = stdlibClasspath(compilerJars)
    val dir = Files.createTempDirectory("scala3-bench-src")
    val file = dir.resolve("Hello.scala")
    Files.write(
      file,
      """@main def hello(): Unit =
        |  println("Hello, World!")
        |  val xs = (1 to 100).map(_ * 2).filter(_ > 50)
        |  println(xs.sum)
        |""".stripMargin.getBytes
    )
    Scala3BenchSources(
      files = Array(file),
      tmpDir = dir,
      compilerArgs = Array("-cp", cp)
    )
  }

  /** Create the scala-yaml benchmark: 33 Scala files, ~7.7K LoC YAML parser.
    * Sources are fetched from lampepfl/scala3-benchmarks (cached).
    * No external dependencies beyond scala-library/scala3-library.
    */
  private def createScalaYaml(compilerJars: Array[File]): Scala3BenchSources = {
    val sourceDir = fetchScalaYamlSources()
    val files = Files.walk(sourceDir).iterator().asScala
      .filter(p => p.toString.endsWith(".scala"))
      .toArray
    println(s"scalaYaml: ${files.length} source files")

    val cp = stdlibClasspath(compilerJars)
    Scala3BenchSources(
      files = files,
      tmpDir = null, // cached, not deleted on teardown
      compilerArgs = Array("-cp", cp)
    )
  }

  private def stdlibClasspath(compilerJars: Array[File]): String = {
    compilerJars.filter { f =>
      val name = f.getName
      name.contains("scala-library") || name.contains("scala3-library")
    }.map(_.getAbsolutePath).mkString(File.pathSeparator)
  }

  private val scalaYamlCacheDir =
    new File(System.getProperty("java.io.tmpdir"), "goron-bench-scala-yaml-src")

  /** Fetch scala-yaml sources, cached on disk. */
  private def fetchScalaYamlSources(): Path = {
    val srcDir = scalaYamlCacheDir.toPath.resolve("src")
    val marker = new File(scalaYamlCacheDir, ".fetched")
    if (marker.exists() && Files.exists(srcDir)) return srcDir

    println("Fetching scala-yaml sources from lampepfl/scala3-benchmarks...")
    scalaYamlCacheDir.mkdirs()

    val tmpClone = Files.createTempDirectory("scala-yaml-clone")
    try {
      val cmds = Seq(
        s"git clone --depth 1 --filter=blob:none --sparse https://github.com/lampepfl/scala3-benchmarks.git ${tmpClone.toAbsolutePath}",
        s"git -C ${tmpClone.toAbsolutePath} sparse-checkout set bench-sources/scalaYaml"
      )
      for (cmd <- cmds) {
        val exitCode = cmd.!
        if (exitCode != 0) throw new RuntimeException(s"Command failed: $cmd")
      }

      // Copy main sources to cache dir (core/shared/src/main has both scala/ and scala-3/)
      val mainSrc = tmpClone.resolve("bench-sources/scalaYaml/core/shared/src/main")
      if (Files.exists(srcDir)) ScalacBenchUtils.deleteRecursive(srcDir)

      Files.walk(mainSrc).forEach { from =>
        val to = srcDir.resolve(mainSrc.relativize(from))
        if (Files.isDirectory(from)) Files.createDirectories(to)
        else {
          Files.createDirectories(to.getParent)
          Files.copy(from, to)
        }
      }

      Files.write(marker.toPath, "ok".getBytes)
      println(s"scala-yaml sources cached at ${scalaYamlCacheDir.getAbsolutePath}")
    } finally {
      ScalacBenchUtils.deleteRecursive(tmpClone)
    }

    srcDir
  }
}
