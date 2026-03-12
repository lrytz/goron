package goron.bench

import goron._

import java.io.File
import java.net.URLClassLoader
import java.security.MessageDigest
import scala.collection.mutable.ListBuffer
import scala.reflect.io.VirtualDirectory
import scala.tools.nsc.reporters.StoreReporter
import scala.tools.nsc.{Global, Settings}

/** Shared infrastructure for goron benchmarks.
  *
  * Provides dependency resolution via coursier, in-process Scala compilation,
  * goron optimization, and classloader isolation for stock vs optimized bytecode.
  *
  * All expensive work (resolution, compilation, optimization) is cached to disk
  * so that JMH forks reuse artifacts instead of recomputing them.
  */
object BenchmarkUtils {

  private val cacheDir = {
    val d = new File(System.getProperty("java.io.tmpdir"), "goron-bench-cache")
    d.mkdirs()
    d
  }

  private def sha256(bytes: Array[Byte]): String = {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(bytes)
    md.digest().take(12).map("%02x".format(_)).mkString
  }

  private def sha256(s: String): String = sha256(s.getBytes("UTF-8"))

  /** Produce a file and cache it. The `produce` function should create the file at the given path.
    * If the cached file already exists, returns it immediately.
    */
  private def cacheFile(key: String, suffix: String, produce: File => Unit): File = {
    val cached = new File(cacheDir, key + suffix)
    if (!cached.exists()) {
      val tmp = new File(cacheDir, key + suffix + ".tmp")
      produce(tmp)
      if (!tmp.renameTo(cached)) {
        // Another process may have written it concurrently
        tmp.delete()
      }
    }
    cached
  }

  /** Resolve a Maven dependency and all its transitive dependencies via coursier.
    * Returns all jar files including transitive deps.
    */
  def resolve(dependencies: String*): Array[File] = {
    import coursier._
    val deps = dependencies.map { coord =>
      val parts = coord.split(":")
      if (parts.length != 3) throw new IllegalArgumentException(s"Expected group:artifact:version, got: $coord")
      Dependency(Module(Organization(parts(0)), ModuleName(parts(1))), parts(2))
    }
    val files = Fetch()
      .addDependencies(deps: _*)
      .run()
    files.toArray
  }

  /** Run goron optimization on a set of jars with given entry points. Returns the optimized jar.
    *
    * Goron runs in a forked JVM process to avoid polluting the benchmark JVM's JIT compiler
    * with goron's own classes (which would add thousands of extra JIT compilations and skew results).
    */
  def optimizeJars(
      jars: Array[File],
      entryPoints: List[String]
  ): File = {
    val outputJar = File.createTempFile("goron-bench-optimized-", ".jar")
    val classpath = System.getProperty("java.class.path")
    val javaHome = System.getProperty("java.home")
    val java = new File(javaHome, "bin/java").getAbsolutePath

    val cmd = ListBuffer(java, "-cp", classpath, "goron.GoronCli")
    for (jar <- jars) { cmd += "--input"; cmd += jar.getAbsolutePath }
    cmd += "--output"; cmd += outputJar.getAbsolutePath
    for (ep <- entryPoints) { cmd += "--entry"; cmd += ep }

    import scala.jdk.CollectionConverters._
    val pb = new ProcessBuilder(cmd.asJava)
    pb.inheritIO()
    val proc = pb.start()
    val exitCode = proc.waitFor()
    if (exitCode != 0)
      throw new RuntimeException(s"Goron subprocess failed with exit code $exitCode")
    outputJar
  }

  /** Compile Scala source code against specific jars.
    * Returns map of className -> bytes.
    * The jars must include scala-library (typically via transitive resolution).
    */
  def compileAgainstJars(code: String, jars: Array[File]): Map[String, Array[Byte]] = {
    def showError(s: String) = throw new Exception(s)
    val settings = new Settings(showError)
    settings.classpath.value = jars.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)
    settings.processArguments(List("-opt:l:none"), processAll = true)
    val global = new Global(settings, new StoreReporter(settings))
    val compiler = new ScalacCompiler(global)
    compiler.global.settings.outputDirs.setSingleOutput(new VirtualDirectory("(memory)", None))
    compiler.compileToBytes(code).map { case (name, bytes) =>
      name.stripSuffix(".class") -> bytes
    }.toMap
  }

  /** Create a URLClassLoader from jar files, isolated from the benchmark classpath.
    * Uses the boot classloader as parent so only JDK classes are inherited.
    */
  def classLoaderFromJars(jars: Array[File]): URLClassLoader = {
    new URLClassLoader(jars.map(_.toURI.toURL), ClassLoader.getSystemClassLoader.getParent)
  }

  /** Write in-memory class bytes to a jar file at the given path. */
  private def writeJarFromBytes(classBytes: Map[String, Array[Byte]], jarFile: File): Unit = {
    import java.util.jar.{JarEntry, JarOutputStream}
    val jos = new JarOutputStream(new java.io.FileOutputStream(jarFile))
    try {
      for ((className, bytes) <- classBytes) {
        val entryName = className.replace('.', '/') + ".class"
        jos.putNextEntry(new JarEntry(entryName))
        jos.write(bytes)
        jos.closeEntry()
      }
    } finally {
      jos.close()
    }
  }

  /** Holds stock and goron driver handles for a benchmark.
    * Call `stock()` and `goron()` to invoke the driver's `run()` method.
    */
  class DriverSetup(
      stockModule: AnyRef,
      stockRun: java.lang.reflect.Method,
      goronModule: AnyRef,
      goronRun: java.lang.reflect.Method
  ) {
    def stock(): AnyRef = stockRun.invoke(stockModule)
    def goron(): AnyRef = goronRun.invoke(goronModule)
  }

  /** Set up a benchmark by compiling driver code, optimizing with goron, and
    * creating isolated classloaders for stock vs optimized bytecode.
    *
    * Goron optimization is deferred until the goron benchmark actually runs,
    * so running only stock benchmarks doesn't pay the optimization cost.
    *
    * @param driverCode Scala source for the driver (must define an `object` with a `run()` method)
    * @param driverObject the simple name of the driver object (e.g., "CatsDriver")
    * @param dependencies Maven coordinates to resolve (e.g., "org.typelevel:cats-core_2.13:2.12.0").
    *                     If empty, uses scala-library from the benchmark classpath.
    */
  def setupDriver(
      driverCode: String,
      driverObject: String,
      dependencies: String*
  ): DriverSetup = {
    val jars = if (dependencies.nonEmpty) {
      resolve(dependencies: _*)
    } else {
      // For micro-benchmarks: resolve scala-library to get an isolated copy
      val scalaVersion = scala.util.Properties.versionNumberString
      resolve(s"org.scala-lang:scala-library:$scalaVersion")
    }

    // Cache key for compilation: hash of driver source + jar paths (sorted for determinism)
    val compileKey = sha256(driverCode + "\u0000" + jars.map(_.getAbsolutePath).sorted.mkString("\u0000"))
    val driverJar = cacheFile(compileKey, "-driver.jar", { out =>
      val driverBytes = compileAgainstJars(driverCode, jars)
      writeJarFromBytes(driverBytes, out)
    })

    val moduleName = driverObject + "$"

    // Stock: original jars + driver jar
    val stockCl = classLoaderFromJars(jars ++ Array(driverJar))
    val (sm, sr) = loadDriver(stockCl, moduleName)

    // Goron: optimize (or load from cache) eagerly
    val allInputJars = jars ++ Array(driverJar)
    val entryPoints = {
      import java.util.jar.JarFile
      val jf = new JarFile(driverJar)
      try {
        val entries = ListBuffer.empty[String]
        val en = jf.entries()
        while (en.hasMoreElements) {
          val e = en.nextElement()
          if (e.getName.endsWith(".class"))
            entries += e.getName.stripSuffix(".class")
        }
        entries.toList
      } finally jf.close()
    }

    val goronKey = sha256(
      allInputJars.map(_.getAbsolutePath).sorted.mkString("\u0000") +
        "\u0000\u0000" + entryPoints.sorted.mkString("\u0000")
    )
    val optimizedJar = cacheFile(goronKey, "-goron.jar", { out =>
      val result = optimizeJars(allInputJars, entryPoints)
      java.nio.file.Files.move(result.toPath, out.toPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    })

    val goronCl = classLoaderFromJars(Array(optimizedJar))
    val (gm, gr) = loadDriver(goronCl, moduleName)

    new DriverSetup(sm, sr, gm, gr)
  }

  private def loadDriver(cl: ClassLoader, moduleName: String): (AnyRef, java.lang.reflect.Method) = {
    val cls = cl.loadClass(moduleName)
    val module = cls.getField("MODULE$").get(null)
    val run = cls.getMethod("run")
    (module, run)
  }

  /** Scala compiler wrapper for compiling source strings to bytecode. */
  class ScalacCompiler(val global: Global) {
    def compileToBytes(code: String): List[(String, Array[Byte])] = {
      global.reporter.reset()
      global.settings.outputDirs.setSingleOutput(new VirtualDirectory("(memory)", None))
      val run = new global.Run()
      val source = new scala.reflect.internal.util.BatchSourceFile("benchSource.scala", code)
      run.compileSources(List(source))
      val reporter = global.reporter.asInstanceOf[StoreReporter]
      val errors = reporter.infos.toList.filter(_.severity == reporter.ERROR)
      if (errors.nonEmpty) {
        throw new RuntimeException("Compilation failed:\n" + errors.mkString("\n"))
      }
      getGeneratedClassfiles(global.settings.outputDirs.getSingleOutput.get)
    }

    private def getGeneratedClassfiles(outDir: scala.tools.nsc.io.AbstractFile): List[(String, Array[Byte])] = {
      val res = ListBuffer.empty[(String, Array[Byte])]
      def collect(dir: scala.tools.nsc.io.AbstractFile): Unit = {
        for (f <- dir.iterator) {
          if (!f.isDirectory) res += ((f.name, f.toByteArray))
          else if (f.name != "." && f.name != "..") collect(f)
        }
      }
      collect(outDir)
      res.toList
    }
  }
}
