/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron.optimizer.analysis

import goron.optimizer.{
  BTypes,
  BTypesFromClassfile,
  CompilerSettings,
  CoreBTypes,
  LabelNode1,
  MethodNode1,
  ClassNode1,
  PerRunInit,
  PostProcessor
}

import scala.tools.asm.tree.analysis._
import scala.tools.asm.tree.{AbstractInsnNode, MethodNode}
import goron.optimizer.BTypes.InternalName
import goron.optimizer.analysis.BackendUtils.computeMaxLocalsMaxStack
import goron.optimizer.opt.BytecodeUtils._

/** A wrapper to make ASM's Analyzer a bit easier to use.
  */
abstract class AsmAnalyzer[V <: Value](
    methodNode: MethodNode,
    classInternalName: InternalName,
    val analyzer: Analyzer[V]
) {
  computeMaxLocalsMaxStack(methodNode)
  try {
    analyzer.analyze(classInternalName, methodNode)
  } catch {
    case ae: AnalyzerException =>
      throw new AnalyzerException(null, "While processing " + classInternalName + "." + methodNode.name, ae)
  }
  def frameAt(instruction: AbstractInsnNode): Frame[V] = analyzer.frameAt(instruction, methodNode)
}

class BasicAnalyzer(methodNode: MethodNode, classInternalName: InternalName)
    extends AsmAnalyzer[BasicValue](methodNode, classInternalName, new Analyzer(new BasicInterpreter))

/** See the doc comment on package object `analysis` for a discussion on performance.
  */
object AsmAnalyzer {
  // jvm limit is 65535 for both number of instructions and number of locals

  private def size(method: MethodNode) = {
    val ml = BackendUtils.maxLocals(method)
    method.instructions.size.toLong * ml * ml
  }

  // with the limits below, analysis should not take more than one second

  private val nullnessSizeLimit = 5000L * 600L * 600L // 5000 insns, 600 locals
  private val basicValueSizeLimit = 9000L * 1000L * 1000L
  private val sourceValueSizeLimit = 8000L * 950L * 950L

  def sizeOKForAliasing(method: MethodNode): Boolean = size(method) < nullnessSizeLimit
  def sizeOKForNullness(method: MethodNode): Boolean = size(method) < nullnessSizeLimit
  def sizeOKForBasicValue(method: MethodNode): Boolean = size(method) < basicValueSizeLimit
  def sizeOKForSourceValue(method: MethodNode): Boolean = size(method) < sourceValueSizeLimit
}
