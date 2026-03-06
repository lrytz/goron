package goron.testkit

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters._
import scala.reflect.io.VirtualDirectory
import scala.tools.asm
import scala.tools.asm.Opcodes
import scala.tools.asm.tree.{ClassNode, MethodNode}
import scala.tools.nsc.{Global, Settings}
import scala.tools.nsc.reporters.StoreReporter

import goron._
import goron.optimizer._
import goron.optimizer.opt.InlineInfoAttributePrototype
import goron.testkit.ASMConverters._

/** Test infrastructure for goron optimizer tests.
  *
  * Provides a Scala compiler (with optimizations disabled) to compile source code strings,
  * then runs the bytecode through goron's optimizer pipeline.
  */
trait GoronTesting extends munit.FunSuite with GoronIntegrationHelpers {

  /** Extra goron config overrides for this test class. */
  def goronConfig: GoronConfig = GoronConfig(
    inputJars = Nil,
    outputJar = "",
    optInlinerEnabled = true,
    optClosureInvocations = true,
    optLocalOptimizations = true,
  )

  // Lazily initialized shared compiler instance (no optimizations)
  lazy val scalac: ScalacCompiler = GoronTesting.newScalac()

  /** Compile Scala source to class bytes (no optimization). */
  def compileToBytes(code: String): List[(String, Array[Byte])] =
    scalac.compileToBytes(code)

  /** Compile Scala source to ClassNodes (no optimization). */
  def compileClasses(code: String): List[ClassNode] =
    scalac.compileToBytes(code).map(p => readClass(p._2)).sortBy(_.name)

  /** Compile Scala source, return single ClassNode. */
  def compileClass(code: String): ClassNode = {
    val classes = compileClasses(code)
    assert(classes.size == 1, s"Expected 1 class, got ${classes.size}: ${classes.map(_.name)}")
    classes.head
  }

  /** Compile Scala source, optimize with goron, return ClassNodes. */
  def compileAndOptimize(code: String): List[ClassNode] = {
    val classBytes = compileToBytes(code)
    optimizeBytes(classBytes)
  }

  /** Optimize pre-compiled class bytes through goron's pipeline. */
  def optimizeBytes(classBytes: List[(String, Array[Byte])]): List[ClassNode] = {
    val pp = GoronTesting.createPostProcessor(goronConfig)
    val classNodes = classBytes.map { case (_, bytes) =>
      val cn = new ClassNode1()
      new asm.ClassReader(bytes).accept(cn, Array[asm.Attribute](InlineInfoAttributePrototype), asm.ClassReader.SKIP_FRAMES)
      cn
    }

    // Add all to ByteCodeRepository as "compiling" classes so the inliner considers them
    for (cn <- classNodes) pp.byteCodeRepository.add(cn, Some("goron-test"))

    // Global optimizations
    if (goronConfig.optInlinerEnabled || goronConfig.optClosureInvocations)
      pp.runGlobalOptimizations(classNodes)

    // Local optimizations
    if (goronConfig.optLocalOptimizations)
      for (cn <- classNodes) pp.localOptimizations(cn)

    classNodes.sortBy(_.name)
  }

  /** Compile, optimize, and return a single class. */
  def compileAndOptimizeClass(code: String): ClassNode = {
    val classes = compileAndOptimize(code)
    assert(classes.size == 1, s"Expected 1 class, got ${classes.size}: ${classes.map(_.name)}")
    classes.head
  }

  // --- Assertion helpers (adapted from BytecodeTesting) ---

  def assertSameCode(method: Method, expected: List[Instruction]): Unit =
    assertSameCode(method.instructions.dropNonOp, expected)

  def assertSameCode(actual: List[Instruction], expected: List[Instruction]): Unit = {
    assert(actual === expected, s"\nExpected: $expected\nActual  : $actual")
  }

  def assertSameSummary(method: Method, expected: List[Any]): Unit =
    assertSameSummary(method.instructions, expected)

  def assertSameSummary(actual: List[Instruction], expected: List[Any]): Unit = {
    def expectedString = expected.map({
      case s: String => s""""$s""""
      case i: Int    => opcodeToString(i, i)
      case x         => throw new MatchError(x)
    }).mkString("List(", ", ", ")")
    assert(actual.summary == expected, s"\nFound   : ${actual.summaryText}\nExpected: $expectedString")
  }

  def assertNoInvoke(m: Method): Unit = assertNoInvoke(m.instructions)
  def assertNoInvoke(ins: List[Instruction]): Unit = {
    assert(!ins.exists(_.isInstanceOf[Invoke]), ins.mkString("\n"))
  }

  def assertInvoke(m: Method, receiver: String, method: String): Unit = assertInvoke(m.instructions, receiver, method)
  def assertInvoke(l: List[Instruction], receiver: String, method: String): Unit = {
    assert(l.exists {
      case Invoke(_, `receiver`, `method`, _, _) => true
      case _ => false
    }, l.mkString("\n"))
  }

  def assertDoesNotInvoke(m: Method, method: String): Unit = assertDoesNotInvoke(m.instructions, method)
  def assertDoesNotInvoke(l: List[Instruction], method: String): Unit = {
    assert(!l.exists {
      case i: Invoke => i.name == method
      case _ => false
    }, l.mkString("\n"))
  }

  def assertInvokedMethods(m: Method, expected: List[String]): Unit = assertInvokedMethods(m.instructions, expected)
  def assertInvokedMethods(l: List[Instruction], expected: List[String]): Unit = {
    def quote(l: List[String]) = l.map(s => s""""$s"""").mkString("List(", ", ", ")")
    val actual = l collect { case i: Invoke => i.owner + "." + i.name }
    assert(actual == expected, s"\nFound   : ${quote(actual)}\nExpected: ${quote(expected)}")
  }

  def assertNoIndy(m: Method): Unit = assertNoIndy(m.instructions)
  def assertNoIndy(l: List[Instruction]): Unit = {
    val indy = l collect { case i: InvokeDynamic => i }
    assert(indy.isEmpty, indy.toString)
  }

  // --- ClassNode / MethodNode helpers ---

  def findClass(cs: List[ClassNode], name: String): ClassNode =
    cs.find(_.name == name).getOrElse(
      throw new AssertionError(s"Class $name not found in ${cs.map(_.name)}"))

  def getAsmMethods(c: ClassNode, p: String => Boolean): List[MethodNode] =
    c.methods.iterator.asScala.filter(m => p(m.name)).toList.sortBy(_.name)

  def getAsmMethods(c: ClassNode, name: String): List[MethodNode] =
    getAsmMethods(c, _ == name)

  def getAsmMethod(c: ClassNode, name: String): MethodNode = {
    val methods = getAsmMethods(c, name)
    assert(methods.size == 1, s"Expected 1 method '$name', found ${methods.size} in ${getAsmMethods(c, _ => true).map(_.name)}")
    methods.head
  }

  def getMethods(c: ClassNode, name: String): List[Method] =
    getAsmMethods(c, name).map(convertMethod)

  def getMethod(c: ClassNode, name: String): Method =
    convertMethod(getAsmMethod(c, name))

  def getInstructions(c: ClassNode, name: String): List[Instruction] =
    getMethod(c, name).instructions

  private def readClass(bytes: Array[Byte]): ClassNode = {
    val cn = new ClassNode()
    new asm.ClassReader(bytes).accept(cn, 0)
    cn
  }
}

/** Scala compiler wrapper for compiling source strings to bytecode. */
class ScalacCompiler(val global: Global) {
  def compileToBytes(code: String): List[(String, Array[Byte])] = {
    global.reporter.reset()
    global.settings.outputDirs.setSingleOutput(new VirtualDirectory("(memory)", None))
    val run = new global.Run()
    val source = new scala.reflect.internal.util.BatchSourceFile("unitTestSource.scala", code)
    run.compileSources(List(source))
    val reporter = global.reporter.asInstanceOf[StoreReporter]
    val errors = reporter.infos.toList.filter(_.severity == reporter.ERROR)
    if (errors.nonEmpty) {
      throw new AssertionError("Compilation failed:\n" + errors.mkString("\n"))
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

/** Helpers for integration tests that run goron over user classes + scala-library. */
trait GoronIntegrationHelpers { self: GoronTesting =>

  /** Compile user code, combine with scala-library, and run the full goron pipeline.
    * Returns surviving ClassNodes after DCE + optimization + DCE.
    */
  def compileAndRunFullPipeline(
    code: String,
    entryPoints: Set[String],
    config: GoronConfig = goronConfig,
  ): List[ClassNode] = {
    val userBytes = compileToBytes(code)
    val userNodes = userBytes.map { case (_, bytes) =>
      val cn = new ClassNode1()
      new asm.ClassReader(bytes).accept(cn, Array[asm.Attribute](InlineInfoAttributePrototype), asm.ClassReader.SKIP_FRAMES)
      cn
    }

    val libNodes = GoronTesting.scalaLibraryNodes

    val allNodes = userNodes ++ libNodes

    // First reachability pass
    val reachableNames = ReachabilityAnalysis.reachableClasses(allNodes, entryPoints)
    val reachableNodes = allNodes.filter(cn => reachableNames.contains(cn.name))
    val unreachableNodes = allNodes.filterNot(cn => reachableNames.contains(cn.name))

    // Set up optimizer
    val pp = GoronTesting.createPostProcessor(config)

    for (cn <- reachableNodes) pp.byteCodeRepository.add(cn, Some("goron-test"))
    for (cn <- unreachableNodes) pp.byteCodeRepository.add(cn, None)

    // Closed-world analysis (matches Goron.run ordering)
    if (config.closedWorld) {
      val hierarchy = ClosedWorldAnalysis.buildHierarchy(allNodes)
      ClosedWorldAnalysis.applyToClassNodes(reachableNodes, hierarchy)
    }

    // Global optimizations
    if (config.optInlinerEnabled || config.optClosureInvocations)
      pp.runGlobalOptimizations(reachableNodes)

    // Local optimizations
    if (config.optLocalOptimizations)
      for (cn <- reachableNodes) pp.localOptimizations(cn)

    // Second DCE pass + method stripping
    if (config.eliminateDeadCode && entryPoints.nonEmpty) {
      val (reachable2, execReachable2, reachableMethods2) =
        ReachabilityAnalysis.reachableClassesAndMethods(reachableNodes, entryPoints)
      val surviving = reachableNodes.filter(cn => reachable2.contains(cn.name))
      ReachabilityAnalysis.stripUnreachableMethods(surviving, reachableMethods2, execReachable2)
      surviving
    } else {
      reachableNodes
    }
  }

  def survivingClassNames(classes: List[ClassNode]): Set[String] =
    classes.map(_.name).toSet

  /** Serialize surviving ClassNodes to bytes, invoke the static main method via a
    * classloader, and return captured stdout.
    */
  def runMain(survivors: List[ClassNode], mainClass: String = "Main"): String = {
    val pp = GoronTesting.createPostProcessor(goronConfig)
    // Add all surviving classes so serializeClass can compute frames
    for (cn <- survivors) pp.byteCodeRepository.add(cn, Some("goron-test"))

    val classBytes = survivors.map { cn =>
      pp.setInnerClasses(cn)
      cn.name.replace('/', '.') -> pp.serializeClass(cn)
    }.toMap

    // Isolated classloader: classes in classBytes are loaded from the optimized
    // bytecode. Scala-library classes NOT in classBytes are blocked (they were
    // DCE'd and shouldn't be available). Only JDK and test infrastructure classes
    // delegate to the parent.
    val allSurvivorNames = classBytes.keySet
    val parentCl = getClass.getClassLoader
    val cl = new ClassLoader(parentCl) {
      override def loadClass(name: String, resolve: Boolean): Class[_] = {
        val already = findLoadedClass(name)
        if (already != null) return already
        classBytes.get(name) match {
          case Some(bytes) => defineClass(name, bytes, 0, bytes.length)
          case None if name.startsWith("scala.") =>
            // Scala-library class not in survivors — it was eliminated.
            // Block it so we detect missing DCE dependencies.
            throw new ClassNotFoundException(s"$name was eliminated by DCE and is not available")
          case None =>
            super.loadClass(name, resolve)
        }
      }
    }

    val mainCls = cl.loadClass(mainClass)
    val mainMethod = mainCls.getMethod("main", classOf[Array[String]])

    val baos = new java.io.ByteArrayOutputStream()
    val oldOut = System.out
    val ps = new java.io.PrintStream(baos)
    try {
      System.setOut(ps)
      mainMethod.invoke(null, Array.empty[String])
    } finally {
      System.setOut(oldOut)
      ps.flush()
    }
    baos.toString.trim
  }
}

object GoronTesting {
  /** Find scala-library.jar from the classloader URL chain. */
  def findScalaLibraryJar(): String = {
    import java.net.URLClassLoader
    val urls = Iterator.iterate(getClass.getClassLoader: ClassLoader)(_.getParent)
      .takeWhile(_ != null)
      .flatMap {
        case ucl: URLClassLoader => ucl.getURLs.iterator
        case _ => Iterator.empty
      }.toList

    val jarUrl = urls.find { u =>
      val path = u.getPath
      path.contains("scala-library") && path.endsWith(".jar")
    }.getOrElse {
      // Fallback: search java.class.path
      val cp = System.getProperty("java.class.path", "")
      val entry = cp.split(java.io.File.pathSeparator).find(p =>
        p.contains("scala-library") && p.endsWith(".jar")
      ).getOrElse(throw new RuntimeException("Cannot find scala-library.jar on classpath"))
      new java.io.File(entry).toURI.toURL
    }
    new java.io.File(jarUrl.toURI).getAbsolutePath
  }

  /** Cached scala-library ClassNodes. Parsed once per JVM. */
  lazy val scalaLibraryNodes: List[ClassNode] = {
    val jarPath = findScalaLibraryJar()
    val entries = JarIO.readJar(jarPath)
    entries.filter(_.isClass).map { entry =>
      val cn = new ClassNode1()
      new asm.ClassReader(entry.bytes).accept(cn, Array[asm.Attribute](InlineInfoAttributePrototype), asm.ClassReader.SKIP_FRAMES)
      cn
    }.toList
  }

  def newScalac(extraArgs: String = ""): ScalacCompiler = {
    def showError(s: String) = throw new Exception(s)
    val settings = new Settings(showError)
    // Build the classpath explicitly from the current classloader so this works
    // both in sbt's in-process test runner and in a forked JVM.
    settings.classpath.value = classPathFromClassLoader(getClass.getClassLoader)
    // No optimizations - goron will handle that
    val args = List("-opt:l:none") ++ (if (extraArgs.nonEmpty) extraArgs.split("\\s+").toList else Nil)
    val (_, nonSettingsArgs) = settings.processArguments(args, processAll = true)
    if (nonSettingsArgs.nonEmpty) showError("invalid compiler flags: " + nonSettingsArgs.mkString(" "))
    val compiler = new ScalacCompiler(new Global(settings, new StoreReporter(settings)))
    compiler.global.settings.outputDirs.setSingleOutput(new VirtualDirectory("(memory)", None))
    compiler
  }

  /** Extract classpath entries from a classloader by walking its parent chain. */
  private def classPathFromClassLoader(cl: ClassLoader): String = {
    import java.net.URLClassLoader
    val urls = Iterator.iterate(cl)(_.getParent).takeWhile(_ != null).flatMap {
      case ucl: URLClassLoader => ucl.getURLs.iterator
      case _ => Iterator.empty
    }.toList
    if (urls.nonEmpty)
      urls.map(u => new java.io.File(u.toURI).getAbsolutePath).mkString(java.io.File.pathSeparator)
    else
      // Last resort: java.class.path (works when the JVM is forked)
      System.getProperty("java.class.path", "")
  }

  def createPostProcessor(config: GoronConfig): PostProcessor = {
    // Use the current classloader so that scala-library classes are findable
    // even in sbt's in-process test runner (where the system classloader doesn't see them)
    val cp = new RuntimeClasspath(new JarClasspath(Map.empty), getClass.getClassLoader)
    val settings = CompilerSettings.fromConfig(config)
    val reporter = BackendReporting.SilentReporter

    val bt: BTypes = new BTypes { btSelf =>
      val compilerSettings: CompilerSettings = settings
      val classpath: Classpath = cp
      val backendReporting: BackendReporting.Reporter = reporter
      def isCompilingPrimitive: Boolean = false
      lazy val coreBTypes: CoreBTypesFromClassfile { val bTypes: btSelf.type } =
        new CoreBTypesFromClassfile { val bTypes: btSelf.type = btSelf }
    }
    val pp = new PostProcessor {
      val bTypes: bt.type = bt
    }
    pp.initialize()
    pp
  }
}
