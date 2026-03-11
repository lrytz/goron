package goron.bench

import goron._

import java.io.File
import java.net.{URL, URLClassLoader}
import scala.collection.mutable.ListBuffer
import scala.reflect.io.VirtualDirectory
import scala.tools.nsc.reporters.StoreReporter
import scala.tools.nsc.{Global, Settings}

/** Shared infrastructure for goron benchmarks.
  *
  * Provides dependency resolution via coursier, in-process Scala compilation,
  * goron optimization, and classloader isolation for stock vs optimized bytecode.
  */
object BenchmarkUtils {

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

  /** Run goron optimization on a set of jars with given entry points. Returns the optimized jar. */
  def optimizeJars(
      jars: Array[File],
      entryPoints: List[String],
      config: GoronConfig = GoronConfig(inputJars = Nil, outputJar = "")
  ): File = {
    val outputJar = File.createTempFile("goron-bench-optimized-", ".jar")
    val fullConfig = config.copy(
      inputJars = jars.map(_.getAbsolutePath).toList,
      outputJar = outputJar.getAbsolutePath,
      entryPoints = entryPoints,
      verbose = false
    )
    Goron.run(fullConfig)
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

  /** Create a ClassLoader that combines jar files with in-memory class bytes.
    * Extra classes take priority over jar contents. Uses boot classloader as parent.
    */
  def classLoaderFromJarsAndBytes(jars: Array[File], extraClasses: Map[String, Array[Byte]]): ClassLoader = {
    val parent = ClassLoader.getSystemClassLoader.getParent
    val urlCl = new URLClassLoader(jars.map(_.toURI.toURL), parent)
    new ClassLoader(urlCl) {
      override def loadClass(name: String, resolve: Boolean): Class[_] = {
        val already = findLoadedClass(name)
        if (already != null) return already
        extraClasses.get(name) match {
          case Some(bytes) =>
            val c = defineClass(name, bytes, 0, bytes.length)
            if (resolve) resolveClass(c)
            c
          case None => super.loadClass(name, resolve)
        }
      }
    }
  }

  /** Create a jar file from in-memory class bytes. */
  def createJarFromBytes(classBytes: Map[String, Array[Byte]]): File = {
    import java.util.jar.{JarEntry, JarOutputStream}
    val jarFile = File.createTempFile("goron-bench-driver-", ".jar")
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
    jarFile
  }

  /** Holds stock and goron driver handles for a benchmark.
    * Call `stock()` and `goron()` to invoke the driver's `run()` method.
    */
  class DriverSetup(
      val stockModule: AnyRef,
      val stockRun: java.lang.reflect.Method,
      val goronModule: AnyRef,
      val goronRun: java.lang.reflect.Method
  ) {
    def stock(): AnyRef = stockRun.invoke(stockModule)
    def goron(): AnyRef = goronRun.invoke(goronModule)
  }

  /** Set up a benchmark by compiling driver code, optimizing with goron, and
    * creating isolated classloaders for stock vs optimized bytecode.
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

    val driverBytes = compileAgainstJars(driverCode, jars)

    // Stock: original jars + driver bytes
    val stockCl = classLoaderFromJarsAndBytes(jars, driverBytes)

    // Goron: run goron on jars + driver, with driver as entry point
    val driverJar = createJarFromBytes(driverBytes)
    val entryPoints = driverBytes.keys.map(_.replace('.', '/')).toList
    val optimizedJar = optimizeJars(jars ++ Array(driverJar), entryPoints)
    val goronCl = classLoaderFromJars(Array(optimizedJar))

    val moduleName = driverObject + "$"
    val (sm, sr) = loadDriver(stockCl, moduleName)
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
