/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron.optimizer

import goron.optimizer.analysis.BackendUtils
import goron.optimizer.opt._

import scala.collection.mutable
import scala.tools.asm.ClassWriter
import scala.tools.asm.tree.ClassNode

/** Implements optimizations, post-processing, and classfile serialization.
  */
final class PostProcessor(
    settings: CompilerSettings,
    classpath: goron.Classpath,
    reporter: BackendReporting.Reporter,
    /** For abstract/interface methods with exactly one concrete implementation:
      * (abstractOwner, name, desc) → concreteImplClass.
      * Populated by closed-world analysis; enables devirtualization in the call graph.
      */
    val singleImplAbstractMethods: Map[(String, String, String), String] = Map.empty
) {

  val bTypes: BTypes = new BTypes(settings, classpath, reporter)
  import bTypes._

  def compilerSettings: CompilerSettings = bTypes.compilerSettings
  def backendReporting: BackendReporting.Reporter = bTypes.backendReporting

  lazy val backendUtils = new BackendUtils[this.type](this)
  lazy val byteCodeRepository = new ByteCodeRepository[this.type](this)
  lazy val localOpt = new LocalOpt[this.type](this)
  lazy val inliner = new Inliner[this.type](this)
  lazy val inlinerHeuristics = new InlinerHeuristics[this.type](this)
  lazy val closureOptimizer = new ClosureOptimizer[this.type](this)
  lazy val callGraph = new CallGraph[this.type](this)
  lazy val bTypesFromClassfile = new BTypesFromClassfile[this.type](this)

  /** Run global optimizations: build call graph, inline, optimize closures. Called by goron after all classes have been
    * added to the ByteCodeRepository.
    */
  def runGlobalOptimizations(classNodes: Iterable[ClassNode]): Unit = {
    if (compilerSettings.optAddToBytecodeRepository) {
      if (compilerSettings.optBuildCallGraph) {
        for (c <- classNodes) {
          callGraph.addClass(c)
        }
      }
      if (compilerSettings.optInlinerEnabled)
        inliner.runInlinerAndClosureOptimizer()
      else if (compilerSettings.optClosureInvocations)
        closureOptimizer.rewriteClosureApplyInvocations(None, mutable.Map.empty)
    }
  }

  def localOptimizations(classNode: ClassNode): Unit = {
    localOpt.methodOptimizations(classNode)
  }

  def setInnerClasses(classNode: ClassNode): Unit = {
    classNode.innerClasses.clear()
    val (declared, referred) = backendUtils.collectNestedClasses(classNode)
    backendUtils.addInnerClasses(classNode, declared, referred)
  }

  def serializeClass(classNode: ClassNode): Array[Byte] = {
    val cw = new ClassWriterWithBTypeLub(backendUtils.extraProc)
    classNode.accept(cw)
    cw.toByteArray
  }

  /** An asm ClassWriter that uses ClassBType.jvmWiseLUB to compute the common superclass of class types. This operation
    * is used for computing stack map frames.
    */
  final class ClassWriterWithBTypeLub(flags: Int) extends ClassWriter(flags) {
    private def resolve(iname: String): ClassBType = {
      val cached = cachedClassBType(iname)
      if (cached ne null) cached
      else bTypesFromClassfile.classBTypeFromParsedClassfile(iname)
    }

    override def getCommonSuperClass(inameA: String, inameB: String): String = {
      try {
        val a = resolve(inameA)
        val b = resolve(inameB)
        val lub = a.jvmWiseLUB(b).get
        val lubName = lub.internalName
        assert(lubName != "scala/Any")
        lubName
      } catch {
        case _: Throwable => "java/lang/Object"
      }
    }
  }
}
