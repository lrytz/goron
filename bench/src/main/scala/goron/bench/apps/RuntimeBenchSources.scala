package goron.bench.apps

import java.io.{ByteArrayOutputStream, File}
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.util.jar.{JarEntry, JarOutputStream}
import scala.reflect.io.VirtualDirectory
import scala.tools.nsc.reporters.StoreReporter
import scala.tools.nsc.{Global, Settings}

/** Extracts and compiles the vendored AWFY runtime benchmark sources. */
object RuntimeBenchSources {

  private val resourceBase = "runtime-bench-sources"

  /** Extract benchmark .scala files from resources to a temp directory. */
  def extractSources(): Array[Path] = {
    val srcDir = Files.createTempDirectory("runtime-bench-src")
    val cl = getClass.getClassLoader

    // Read the manifest of source files
    val listStream = cl.getResourceAsStream(s"$resourceBase/sources.list")
    if (listStream == null) throw new RuntimeException("sources.list not found in resources")
    val sourceNames = try {
      val bytes = readAllBytes(listStream)
      new String(bytes).split("\n").map(_.trim).filter(_.nonEmpty)
    } finally listStream.close()

    val files = sourceNames.flatMap { name =>
      val stream = cl.getResourceAsStream(s"$resourceBase/$name")
      if (stream == null) {
        System.err.println(s"Warning: resource $resourceBase/$name not found, skipping")
        None
      } else {
        try {
          val dest = srcDir.resolve(name)
          Files.createDirectories(dest.getParent)
          val bytes = readAllBytes(stream)
          Files.write(dest, bytes)
          Some(dest)
        } finally stream.close()
      }
    }

    println(s"Extracted ${files.length} benchmark source files")
    files
  }

  /** Compile source files with the Scala 2 compiler and produce a jar. */
  def compileSources(sourceFiles: Array[Path], compilerJars: Array[File]): File = {
    val cacheDir = new File(System.getProperty("java.io.tmpdir"), "goron-bench-cache")
    cacheDir.mkdirs()

    // Cache key based on source file contents + compiler version
    val md = MessageDigest.getInstance("SHA-256")
    for (f <- sourceFiles.sortBy(_.toString)) md.update(Files.readAllBytes(f))
    for (j <- compilerJars.sortBy(_.getName)) md.update(j.getAbsolutePath.getBytes)
    val key = md.digest().take(12).map("%02x".format(_)).mkString
    val cached = new File(cacheDir, s"$key-awfy.jar")
    if (cached.exists()) {
      println(s"Using cached compiled benchmarks: $cached")
      return cached
    }

    println(s"Compiling ${sourceFiles.length} benchmark sources...")

    def showError(s: String) = throw new Exception(s)
    val settings = new Settings(showError)
    val scalaLibrary = compilerJars.find(_.getName.contains("scala-library"))
      .getOrElse(throw new RuntimeException("scala-library not found"))
    settings.classpath.value = scalaLibrary.getAbsolutePath
    settings.processArguments(List("-opt:l:none"), processAll = true)

    val reporter = new StoreReporter(settings)
    val global = new Global(settings, reporter)
    global.settings.outputDirs.setSingleOutput(new VirtualDirectory("(memory)", None))

    val run = new global.Run()
    val sources = sourceFiles.map { p =>
      new scala.reflect.internal.util.BatchSourceFile(p.toString, new String(Files.readAllBytes(p)))
    }.toList
    run.compileSources(sources)

    val errors = reporter.infos.toList.filter(_.severity == reporter.ERROR)
    if (errors.nonEmpty)
      throw new AssertionError(s"Benchmark compilation failed:\n${errors.mkString("\n")}")

    // Collect classfiles from the virtual output directory
    val outDir = global.settings.outputDirs.getSingleOutput.get
    val jos = new JarOutputStream(new java.io.FileOutputStream(cached))
    try {
      def collect(dir: scala.tools.nsc.io.AbstractFile, prefix: String): Unit = {
        for (f <- dir.iterator) {
          if (!f.isDirectory) {
            val entryName = if (prefix.isEmpty) f.name else s"$prefix/${f.name}"
            jos.putNextEntry(new JarEntry(entryName))
            jos.write(f.toByteArray)
            jos.closeEntry()
          } else if (f.name != "." && f.name != "..") {
            collect(f, if (prefix.isEmpty) f.name else s"$prefix/${f.name}")
          }
        }
      }
      collect(outDir, "")
    } finally {
      jos.close()
    }

    println(s"Compiled benchmarks to $cached")
    cached
  }

  private def readAllBytes(in: java.io.InputStream): Array[Byte] = {
    val buf = new ByteArrayOutputStream()
    val tmp = new Array[Byte](8192)
    var n = in.read(tmp)
    while (n != -1) {
      buf.write(tmp, 0, n)
      n = in.read(tmp)
    }
    buf.toByteArray
  }
}
