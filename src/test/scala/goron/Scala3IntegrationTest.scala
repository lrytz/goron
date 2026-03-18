/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron

import goron.optimizer._
import goron.optimizer.opt.InlineInfoAttributePrototype
import goron.testkit.ASMConverters._
import goron.testkit.GoronTesting

import java.io.File
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters._
import scala.sys.process._
import scala.tools.asm
import scala.tools.asm.tree.ClassNode

/** Integration tests for Scala 3 bytecode processed through goron. */
class Scala3IntegrationTest extends GoronTesting {

  override def goronConfig = super.goronConfig.copy(
    optInlinerEnabled = true,
    optClosureInvocations = true,
    optLocalOptimizations = true,
    eliminateDeadCode = true,
    closedWorld = true
  )

  private val scala3Available: Boolean = {
    try {
      val cmd = """bash -c 'source $HOME/.sdkman/bin/sdkman-init.sh && scala-cli version 2>/dev/null'"""
      cmd.!!.trim.nonEmpty
    } catch { case _: Exception => false }
  }

  /** Compile Scala 3 source via scala-cli, return classfile bytes. */
  private def compileScala3(code: String): List[(String, Array[Byte])] = {
    val tmpDir = Files.createTempDirectory("goron-scala3-test")
    try {
      val srcFile = tmpDir.resolve("Main.scala")
      Files.writeString(srcFile, code)
      val outDir = tmpDir.resolve("out")
      Files.createDirectory(outDir)

      val cmd = s"""bash -c 'source $$HOME/.sdkman/bin/sdkman-init.sh && scala-cli compile --scala 3.8.2 --server=false -O -d -O ${outDir.toAbsolutePath} ${srcFile.toAbsolutePath} 2>&1'"""
      val output = cmd.!!.trim

      // Collect .class files (skip .tasty)
      val classFiles = Files.walk(outDir).iterator().asScala
        .filter(p => p.toString.endsWith(".class"))
        .toList
      if (classFiles.isEmpty)
        throw new AssertionError(s"Scala 3 compilation produced no classfiles:\n$output")

      classFiles.map { p =>
        val name = outDir.relativize(p).toString.replace(File.separator, "/")
        (name, Files.readAllBytes(p))
      }
    } finally {
      Files.walk(tmpDir).sorted(java.util.Comparator.reverseOrder())
        .forEach(Files.deleteIfExists(_))
    }
  }

  /** Run the full goron pipeline on pre-compiled classfile bytes. */
  private def runFullPipelineFromBytes(
      classBytes: List[(String, Array[Byte])],
      entryPoints: Set[String]
  ): List[ClassNode] = {
    val userNodes = classBytes.map { case (_, bytes) =>
      val cn = new ClassNode1()
      new asm.ClassReader(bytes)
        .accept(cn, Array[asm.Attribute](InlineInfoAttributePrototype), asm.ClassReader.SKIP_FRAMES)
      cn
    }

    val libNodes = GoronTesting.scalaLibraryNodes
    val allNodes = userNodes ++ libNodes

    val allHierarchy = ClassHierarchy.build(allNodes)
    val reachableNames = ReachabilityAnalysis.reachableClasses(allHierarchy, entryPoints)
    val reachableNodes = allNodes.filter(cn => reachableNames.contains(cn.name))
    val unreachableNodes = allNodes.filterNot(cn => reachableNames.contains(cn.name))

    val config = goronConfig
    val pp = GoronTesting.createPostProcessor(config)

    for (cn <- reachableNodes) pp.byteCodeRepository.add(cn, Some("goron-test"))
    for (cn <- unreachableNodes) pp.byteCodeRepository.add(cn, None)

    if (config.closedWorld) {
      val closedWorld = ClosedWorldAnalysis.buildHierarchy(allHierarchy)
      ClosedWorldAnalysis.applyToClassNodes(reachableNodes, closedWorld)
    }

    if (config.optInlinerEnabled || config.optClosureInvocations)
      pp.runGlobalOptimizations(reachableNodes)

    if (config.optLocalOptimizations)
      for (cn <- reachableNodes) pp.localOptimizations(cn)

    if (config.eliminateDeadCode && entryPoints.nonEmpty) {
      val (reachable2, execReachable2, reachableMethods2) =
        ReachabilityAnalysis.reachableClassesAndMethods(ClassHierarchy.build(reachableNodes), entryPoints)
      val surviving = reachableNodes.filter(cn => reachable2.contains(cn.name))
      ReachabilityAnalysis.stripUnreachableMethods(surviving, reachableMethods2, execReachable2)
      surviving
    } else {
      reachableNodes
    }
  }

  test("Scala 3: for loop with println is optimized") {
    assume(scala3Available, "scala-cli not available, skipping Scala 3 tests")

    val code =
      """object Main {
        |  def main(args: Array[String]): Unit = {
        |    for (i <- 1 to 10) println(i)
        |  }
        |}
        |""".stripMargin

    val classBytes = compileScala3(code)
    assert(classBytes.exists(_._1.contains("Main")), s"Expected Main class, got: ${classBytes.map(_._1)}")

    val survivors = runFullPipelineFromBytes(classBytes, Set("Main"))
    assertEquals(runMain(survivors), (1 to 10).mkString("\n"))

    // Check what got inlined
    val mainClass = findClass(survivors, "Main$")
    val mainMethod = getMethod(mainClass, "main")
    val invokes = mainMethod.instructions.collect { case i: Invoke => s"${i.owner}.${i.name}" }
    println(s"=== Scala 3 for-loop invocations: ${invokes.mkString(", ")} ===")

    // The foreach lambda should be inlined — no anonfun or foreach remnants
    val hasForEachCall = invokes.exists(i => i.contains("foreach"))
    assert(!hasForEachCall, s"foreach should be inlined. Invocations:\n${invokes.mkString("\n  ")}")
  }
}
