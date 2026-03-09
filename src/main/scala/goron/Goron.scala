/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron

import scala.tools.asm
import scala.tools.asm.tree.ClassNode
import goron.optimizer._
import goron.optimizer.opt.InlineInfoAttributePrototype

object Goron {
  def run(config: GoronConfig): Unit = {
    val totalStart = System.nanoTime()
    def elapsed(start: Long): String = f"${(System.nanoTime() - start) / 1e9}%.1fs"
    def log(msg: String): Unit = if (config.verbose) println(msg)

    log(s"Reading ${config.inputJars.size} input jar(s)...")
    var phaseStart = System.nanoTime()

    val allEntries = JarIO.readJars(config.inputJars)
    val classEntries = allEntries.filter(_.isClass)
    val resourceEntries = allEntries.filterNot(_.isClass)

    log(s"  ${classEntries.size} classes, ${resourceEntries.size} resources (${elapsed(phaseStart)})")

    // Build classpath from all class entries, with JDK runtime fallback
    val classpath = new RuntimeClasspath(
      JarClasspath.fromClassEntries(classEntries.map(e => (e.name, e.bytes)))
    )

    // Create the optimizer pipeline
    val settings = CompilerSettings.fromConfig(config)
    val reporter =
      if (config.verbose) BackendReporting.ConsoleReporter
      else BackendReporting.SilentReporter
    val pp = createPostProcessor(settings, classpath, reporter)

    // Parse all class entries into ClassNodes (using ClassNode1 for LabelNode1 support)
    log("Parsing class files...")
    phaseStart = System.nanoTime()

    val classNodes = classEntries.map { entry =>
      val cn = new ClassNode1()
      val cr = new asm.ClassReader(entry.bytes)
      cr.accept(cn, Array[asm.Attribute](InlineInfoAttributePrototype), asm.ClassReader.SKIP_FRAMES)
      cn
    }

    log(s"  ${classNodes.size} classes parsed (${elapsed(phaseStart)})")

    // Determine reachable classes first — only these will be optimized.
    // All classes are added to ByteCodeRepository for type resolution, but only
    // reachable classes are added as "compiling" (eligible for inlining into).
    val progressLog: String => Unit = msg => log(msg)

    val reachableNames = if (config.entryPoints.nonEmpty) {
      log("Reachability analysis...")
      phaseStart = System.nanoTime()
      val reachable = ReachabilityAnalysis.reachableClasses(classNodes, config.entryPoints.toSet, progressLog)
      log(s"  ${reachable.size} of ${classNodes.size} classes reachable (${elapsed(phaseStart)})")
      reachable
    } else {
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

    // Closed-world analysis: mark effectively-final classes/methods before inlining
    if (config.closedWorld) {
      log("Closed-world analysis...")
      phaseStart = System.nanoTime()
      val hierarchy = ClosedWorldAnalysis.buildHierarchy(classNodes)
      ClosedWorldAnalysis.applyToClassNodes(reachableClassNodes, hierarchy)
      log(
        s"  ${hierarchy.effectivelyFinalClasses.size} final classes, " +
          s"${hierarchy.effectivelyFinalMethods.size} final methods (${elapsed(phaseStart)})"
      )
    }

    // Run global optimizations (inlining, closure optimization) on reachable classes
    if (config.optInlinerEnabled || config.optClosureInvocations) {
      log("Inlining and closure optimization...")
      phaseStart = System.nanoTime()
      pp.runGlobalOptimizations(reachableClassNodes)
      log(s"  Done (${elapsed(phaseStart)})")
    }

    // Run local optimizations per reachable class
    if (config.optLocalOptimizations) {
      log("Local optimizations...")
      phaseStart = System.nanoTime()
      for (cn <- reachableClassNodes) {
        pp.localOptimizations(cn)
      }
      log(s"  ${reachableClassNodes.size} classes optimized (${elapsed(phaseStart)})")
    }

    var strippedMethods = 0
    val outputClassNodes = if (config.eliminateDeadCode && config.entryPoints.nonEmpty) {
      log("Dead code elimination...")
      phaseStart = System.nanoTime()
      val (reachable, execReachable, reachableMethods) =
        ReachabilityAnalysis.reachableClassesAndMethods(reachableClassNodes, config.entryPoints.toSet, progressLog)
      val surviving = reachableClassNodes.filter(cn => reachable.contains(cn.name))
      strippedMethods = ReachabilityAnalysis.stripUnreachableMethods(surviving, reachableMethods, execReachable)
      val removedClasses = reachableClassNodes.size - surviving.size
      log(
        s"  ${surviving.size} classes retained, $removedClasses removed, $strippedMethods methods stripped (${elapsed(phaseStart)})"
      )
      surviving
    } else {
      reachableClassNodes
    }

    log("Serializing and writing output...")
    phaseStart = System.nanoTime()

    val optimizedEntries = outputClassNodes.map { cn =>
      pp.setInnerClasses(cn)
      val bytes = pp.serializeClass(cn)
      JarIO.JarEntry(cn.name + ".class", bytes, isClass = true)
    }

    JarIO.writeJar(config.outputJar, optimizedEntries ++ resourceEntries)
    log(s"  ${config.outputJar} (${elapsed(phaseStart)})")

    val inputSize = config.inputJars.map(j => new java.io.File(j).length()).sum
    val outputSize = new java.io.File(config.outputJar).length()
    println(
      s"goron: ${classNodes.size} → ${outputClassNodes.size} classes" +
        s", ${strippedMethods} methods stripped" +
        s", ${formatSize(inputSize)} → ${formatSize(outputSize)}" +
        s" (${elapsed(totalStart)})"
    )
  }

  private def formatSize(bytes: Long): String = {
    if (bytes < 1024) s"${bytes}B"
    else if (bytes < 1024 * 1024) f"${bytes / 1024.0}%.0fK"
    else f"${bytes / (1024.0 * 1024.0)}%.1fM"
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
      lazy val coreBTypes: CoreBTypesFromClassfile { val bTypes: btSelf.type } =
        new CoreBTypesFromClassfile { val bTypes: btSelf.type = btSelf }
    }
    new PostProcessor {
      val bTypes: bt.type = bt
    }
  }
}
