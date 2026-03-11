package goron.bench

import goron._
import goron.optimizer._
import goron.optimizer.opt.InlineInfoAttributePrototype

import java.io.File
import java.net.{URL, URLClassLoader}
import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable.ListBuffer
import scala.reflect.io.VirtualDirectory
import scala.tools.asm
import scala.tools.asm.tree.ClassNode
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

  /** Create a new in-process Scala compiler (with optimizations disabled). */
  def newScalac(): ScalacCompiler = {
    def showError(s: String) = throw new Exception(s)
    val settings = new Settings(showError)
    settings.classpath.value = classPathFromClassLoader(getClass.getClassLoader)
    val args = List("-opt:l:none")
    settings.processArguments(args, processAll = true)
    val compiler = new ScalacCompiler(new Global(settings, new StoreReporter(settings)))
    compiler.global.settings.outputDirs.setSingleOutput(new VirtualDirectory("(memory)", None))
    compiler
  }

  /** Create a goron PostProcessor for optimizing class bytes. */
  def createPostProcessor(config: GoronConfig = defaultConfig): PostProcessor = {
    val cp = new RuntimeClasspath(new JarClasspath(Map.empty), getClass.getClassLoader)
    val settings = CompilerSettings.fromConfig(config)
    new PostProcessor(settings, cp, BackendReporting.SilentReporter)
  }

  /** Compile source code, then optimize with goron. Returns (stockClassBytes, optimizedClassBytes).
    *
    * The compiled code is optimized in isolation (without scala-library in the optimization scope).
    * Both stock and optimized bytecode still reference scala-library classes at runtime.
    */
  def compileAndOptimize(
      code: String,
      config: GoronConfig = defaultConfig
  ): (Map[String, Array[Byte]], Map[String, Array[Byte]]) = {
    val compiler = newScalac()
    val classBytes = compiler.compileToBytes(code)
    val stockMap = classBytes.map { case (name, bytes) =>
      name.stripSuffix(".class") -> bytes
    }.toMap

    val pp = createPostProcessor(config)
    val classNodes = classBytes.map { case (_, bytes) =>
      val cn = new ClassNode1()
      new asm.ClassReader(bytes)
        .accept(cn, Array[asm.Attribute](InlineInfoAttributePrototype), asm.ClassReader.SKIP_FRAMES)
      cn
    }

    for (cn <- classNodes) pp.byteCodeRepository.add(cn, Some("goron-bench"))

    // Closed-world analysis: mark effectively-final classes/methods so the inliner
    // can prove they're safe to inline. Without this, inlining doesn't happen.
    if (config.closedWorld) {
      val hierarchy = ClassHierarchy.build(classNodes)
      val closedWorld = ClosedWorldAnalysis.buildHierarchy(hierarchy)
      ClosedWorldAnalysis.applyToClassNodes(classNodes, closedWorld)
    }

    if (config.optInlinerEnabled || config.optClosureInvocations)
      pp.runGlobalOptimizations(classNodes)

    if (config.optLocalOptimizations)
      for (cn <- classNodes) pp.localOptimizations(cn)

    val optimizedMap = classNodes.map { cn =>
      pp.setInnerClasses(cn)
      cn.name.replace('/', '.') -> pp.serializeClass(cn)
    }.toMap

    (stockMap, optimizedMap)
  }

  /** Create a ClassLoader that loads classes from in-memory byte arrays.
    * Uses the benchmark classloader as parent so scala-library classes are available
    * (the compiled micro-benchmark code still references scala-library at runtime).
    */
  def classLoaderFromBytes(classBytes: Map[String, Array[Byte]]): ClassLoader = {
    val parent = getClass.getClassLoader
    new ClassLoader(parent) {
      override def loadClass(name: String, resolve: Boolean): Class[_] = {
        val already = findLoadedClass(name)
        if (already != null) return already
        classBytes.get(name) match {
          case Some(bytes) => defineClass(name, bytes, 0, bytes.length)
          case None        => super.loadClass(name, resolve)
        }
      }
    }
  }

  /** Create a URLClassLoader from jar files, isolated from the benchmark classpath.
    * Uses the boot classloader as parent so only JDK classes are inherited.
    * Suitable for app benchmarks where the jars contain all needed classes (including scala-library).
    */
  def classLoaderFromJars(jars: Array[File]): URLClassLoader = {
    new URLClassLoader(jars.map(_.toURI.toURL), ClassLoader.getSystemClassLoader.getParent)
  }

  val defaultConfig: GoronConfig = GoronConfig(
    inputJars = Nil,
    outputJar = "",
    optInlinerEnabled = true,
    optClosureInvocations = true,
    optLocalOptimizations = true,
    optBoxUnbox = true,
    optCopyPropagation = true,
    optDeadCode = true,
    optUnreachableCode = true
  )

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

  private def classPathFromClassLoader(cl: ClassLoader): String = {
    import java.net.URLClassLoader
    val urls = Iterator
      .iterate(cl)(_.getParent)
      .takeWhile(_ != null)
      .flatMap {
        case ucl: URLClassLoader => ucl.getURLs.iterator
        case _                   => Iterator.empty
      }
      .toList
    if (urls.nonEmpty)
      urls.map(u => new java.io.File(u.toURI).getAbsolutePath).mkString(java.io.File.pathSeparator)
    else
      System.getProperty("java.class.path", "")
  }
}
