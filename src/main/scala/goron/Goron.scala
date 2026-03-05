package goron

import scala.tools.asm
import scala.tools.asm.tree.ClassNode
import goron.optimizer._

object Goron {
  def run(config: GoronConfig): Unit = {
    if (config.verbose) println(s"Reading ${config.inputJars.size} input jar(s)...")

    val allEntries = JarIO.readJars(config.inputJars)
    val classEntries = allEntries.filter(_.isClass)
    val resourceEntries = allEntries.filterNot(_.isClass)

    if (config.verbose) {
      println(s"  ${classEntries.size} class files")
      println(s"  ${resourceEntries.size} resource files")
    }

    // Build classpath from all class entries, with JDK runtime fallback
    val classpath = new RuntimeClasspath(
      JarClasspath.fromClassEntries(classEntries.map(e => (e.name, e.bytes)))
    )

    // Create the optimizer pipeline
    val settings = CompilerSettings.fromConfig(config)
    val reporter = if (config.verbose) BackendReporting.ConsoleReporter
                   else BackendReporting.SilentReporter
    val pp = createPostProcessor(settings, classpath, reporter)
    pp.initialize()

    // Parse all class entries into ClassNodes
    val classNodes = classEntries.map { entry =>
      val cn = new ClassNode()
      val cr = new asm.ClassReader(entry.bytes)
      cr.accept(cn, asm.ClassReader.SKIP_FRAMES)
      cn
    }

    // Add all classes to the ByteCodeRepository
    for (cn <- classNodes) {
      pp.byteCodeRepository.add(cn, None)
    }

    if (config.verbose) println("Running optimizations...")

    // Run global optimizations (inlining, closure optimization) if enabled
    if (config.optInlinerEnabled || config.optClosureInvocations) {
      pp.runGlobalOptimizations(classNodes)
    }

    // Run local optimizations per class
    if (config.optLocalOptimizations) {
      for (cn <- classNodes) {
        pp.localOptimizations(cn)
      }
    }

    // Dead code elimination: filter to reachable classes only
    val outputClassNodes = if (config.eliminateDeadCode && config.entryPoints.nonEmpty) {
      val reachable = ReachabilityAnalysis.reachableClasses(classNodes, config.entryPoints.toSet)
      if (config.verbose) {
        val removed = classNodes.size - reachable.size
        println(s"  Dead code elimination: keeping ${reachable.size} of ${classNodes.size} classes ($removed removed)")
      }
      classNodes.filter(cn => reachable.contains(cn.name))
    } else {
      classNodes
    }

    // Serialize optimized classes back to bytes
    val optimizedEntries = outputClassNodes.map { cn =>
      pp.setInnerClasses(cn)
      val bytes = pp.serializeClass(cn)
      JarIO.JarEntry(cn.name + ".class", bytes, isClass = true)
    }

    if (config.verbose) println(s"Writing output jar: ${config.outputJar}")
    JarIO.writeJar(config.outputJar, optimizedEntries ++ resourceEntries)
    if (config.verbose) println("Done.")
  }

  /** Create a fully wired PostProcessor with the cake pattern. */
  private def createPostProcessor(
    settings: CompilerSettings,
    cp: Classpath,
    reporter: BackendReporting.Reporter
  ): PostProcessor = {
    val bt: BTypes = new BTypes { btSelf =>
      val compilerSettings: CompilerSettings = settings
      val classpath: Classpath = cp
      val backendReporting: BackendReporting.Reporter = reporter
      def isCompilingPrimitive: Boolean = false
      val coreBTypes: CoreBTypesFromClassfile { val bTypes: btSelf.type } =
        new { val bTypes: btSelf.type = btSelf } with CoreBTypesFromClassfile
    }
    new PostProcessor {
      val bTypes: bt.type = bt
    }
  }
}
