package goron

import scala.tools.asm
import scala.tools.asm.tree.ClassNode
import goron.optimizer._
import goron.optimizer.opt.InlineInfoAttributePrototype

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

    // Parse all class entries into ClassNodes (using ClassNode1 for LabelNode1 support)
    val classNodes = classEntries.map { entry =>
      val cn = new ClassNode1()
      val cr = new asm.ClassReader(entry.bytes)
      cr.accept(cn, Array[asm.Attribute](InlineInfoAttributePrototype), asm.ClassReader.SKIP_FRAMES)
      cn
    }

    // Determine reachable classes first — only these will be optimized.
    // All classes are added to ByteCodeRepository for type resolution, but only
    // reachable classes are added as "compiling" (eligible for inlining into).
    val reachableNames = if (config.entryPoints.nonEmpty) {
      val reachable = ReachabilityAnalysis.reachableClasses(classNodes, config.entryPoints.toSet)
      if (config.verbose) {
        println(s"  Reachability: ${reachable.size} of ${classNodes.size} classes reachable from entry points")
      }
      reachable
    } else {
      // No entry points specified — treat all classes as reachable
      classNodes.map(_.name).toSet
    }

    // Add reachable classes as "compiling" (inliner will optimize these),
    // unreachable classes as "parsed" (available for type resolution only)
    for (cn <- classNodes) {
      if (reachableNames.contains(cn.name))
        pp.byteCodeRepository.add(cn, Some("goron"))
      else
        pp.byteCodeRepository.add(cn, None)
    }

    val reachableClassNodes = classNodes.filter(cn => reachableNames.contains(cn.name))

    if (config.verbose) println("Running optimizations...")

    // Closed-world analysis: mark effectively-final classes/methods before inlining
    if (config.closedWorld) {
      // Analyze hierarchy across ALL classes for accurate finality analysis
      val hierarchy = ClosedWorldAnalysis.buildHierarchy(classNodes)
      if (config.verbose) {
        println(s"  Closed-world: ${hierarchy.effectivelyFinalClasses.size} effectively-final classes, " +
          s"${hierarchy.effectivelyFinalMethods.size} effectively-final methods")
      }
      // But only apply finality markers to reachable classes (the ones we'll serialize)
      ClosedWorldAnalysis.applyToClassNodes(reachableClassNodes, hierarchy)
    }

    // Run global optimizations (inlining, closure optimization) on reachable classes
    if (config.optInlinerEnabled || config.optClosureInvocations) {
      pp.runGlobalOptimizations(reachableClassNodes)
    }

    // Run local optimizations per reachable class
    if (config.optLocalOptimizations) {
      for (cn <- reachableClassNodes) {
        pp.localOptimizations(cn)
      }
    }

    val outputClassNodes = if (config.eliminateDeadCode && config.entryPoints.nonEmpty) {
      // Run DCE again — inlining may have made more classes unreachable
      val reachable = ReachabilityAnalysis.reachableClasses(reachableClassNodes, config.entryPoints.toSet)
      if (config.verbose) {
        val removed = reachableClassNodes.size - reachable.size
        println(s"  Dead code elimination: keeping ${reachable.size} of ${reachableClassNodes.size} classes ($removed removed)")
      }
      reachableClassNodes.filter(cn => reachable.contains(cn.name))
    } else {
      reachableClassNodes
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
