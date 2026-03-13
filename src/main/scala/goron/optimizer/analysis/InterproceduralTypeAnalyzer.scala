/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron.optimizer.analysis

import goron.optimizer.analysis.TypeFlowInterpreter._
import goron.optimizer.opt.ByteCodeRepository
import goron.optimizer.opt.BytecodeUtils._

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.tools.asm.tree._
import scala.tools.asm.tree.analysis.{Analyzer, BasicValue}
import scala.tools.asm.{Opcodes, Type}

/** Result of interprocedural type analysis for a method return type. */
sealed trait AnalyzedReturnType {
  def internalName: String
}

/** The method is known to always return an instance of exactly this type. */
case class ExactReturnType(internalName: String) extends AnalyzedReturnType

/** The method returns a value that is at most this type (could be a subclass). */
case class NarrowedReturnType(internalName: String) extends AnalyzedReturnType

/**
 * ExactTypeValue from interprocedural analysis (not from JVM instructions like NEW or GETSTATIC).
 * The JVM verifier does not know the precise type, so a CHECKCAST must be inserted when
 * inlining a method resolved from this type.
 */
class InterproceduralExactTypeValue(tpe: Type) extends ExactTypeValue(tpe)

/**
 * Demand-driven interprocedural type analysis. Given a callsite where the receiver type
 * is known, traces through method bodies to determine concrete return types. This enables
 * devirtualization of calls that are otherwise blocked by interface dispatch.
 *
 * Key capabilities:
 * - Traces return types through method bodies (NEW, GETSTATIC MODULE$, method delegation)
 * - Singleton field tracking: determines field values of Scala objects by analyzing constructor chains
 * - Context-sensitive: uses the known receiver type to resolve virtual calls within method bodies
 * - Depth-limited and cached to bound analysis cost
 */
class InterproceduralTypeAnalyzer(byteCodeRepository: ByteCodeRepository[_]) {

  private val returnTypeCache = mutable.Map.empty[(String, String, String), Option[AnalyzedReturnType]]
  private val fieldValueCache = mutable.Map.empty[String, Map[(String, String), AnalyzedReturnType]]
  private val inProgressReturnType = mutable.Set.empty[(String, String, String)]
  private val inProgressFieldValues = mutable.Set.empty[String]

  /**
   * Analyze the return type of a method when called on a receiver of known type.
   *
   * @param receiverType internal name of the known receiver type
   * @param methodName method name
   * @param methodDesc method descriptor
   * @param depth remaining analysis depth (decremented on each recursive call)
   * @return Some(type) if a more precise return type could be determined, None otherwise
   */
  def analyzeReturnType(
      receiverType: String,
      methodName: String,
      methodDesc: String,
      depth: Int = 5
  ): Option[AnalyzedReturnType] = {
    if (depth <= 0) return None
    val key = (receiverType, methodName, methodDesc)
    returnTypeCache.getOrElseUpdate(key, {
      if (inProgressReturnType.contains(key)) None
      else {
        inProgressReturnType += key
        try analyzeReturnTypeImpl(receiverType, methodName, methodDesc, depth)
        finally inProgressReturnType -= key
      }
    })
  }

  private def analyzeReturnTypeImpl(
      receiverType: String,
      methodName: String,
      methodDesc: String,
      depth: Int
  ): Option[AnalyzedReturnType] = {
    val returnType = Type.getReturnType(methodDesc)
    if (returnType.getSort != Type.OBJECT && returnType.getSort != Type.ARRAY) return None

    byteCodeRepository.methodNode(receiverType, methodName, methodDesc) match {
      case Right((methodNode, declarationClass)) =>
        if (isAbstractMethod(methodNode) || isNativeMethod(methodNode)) return None
        if (!AsmAnalyzer.sizeOKForBasicValue(methodNode)) return None
        analyzeMethodBody(methodNode, declarationClass, receiverType, depth)
      case Left(_) => None
    }
  }

  private def analyzeMethodBody(
      methodNode: MethodNode,
      declarationClass: String,
      receiverType: String,
      depth: Int
  ): Option[AnalyzedReturnType] = {
    val interpreter = new InterproceduralTypeFlowInterpreter(this, receiverType, depth - 1)
    val analyzer = new Analyzer(interpreter)

    try {
      val frames = analyzer.analyze(declarationClass, methodNode)
      val insns = methodNode.instructions.toArray

      // Collect analyzed types at all ARETURN instructions
      val returnTypes = mutable.ListBuffer.empty[Option[AnalyzedReturnType]]
      for (i <- insns.indices) {
        if (insns(i) != null && insns(i).getOpcode == Opcodes.ARETURN) {
          val frame = frames(i)
          if (frame != null) {
            val stackTop = frame.getStack(frame.getStackSize - 1)
            returnTypes += valueToAnalyzedType(stackTop)
          }
        }
      }

      if (returnTypes.isEmpty) return None

      val knownTypes = returnTypes.flatten
      if (knownTypes.nonEmpty && knownTypes.size == returnTypes.size) {
        val allSameName = knownTypes.forall(_.internalName == knownTypes.head.internalName)
        if (allSameName) {
          val allExact = knownTypes.forall(_.isInstanceOf[ExactReturnType])
          if (allExact) Some(ExactReturnType(knownTypes.head.internalName))
          else Some(NarrowedReturnType(knownTypes.head.internalName))
        } else {
          declaredReturnType(methodNode)
        }
      } else {
        declaredReturnType(methodNode)
      }
    } catch {
      case _: Exception => None
    }
  }

  private def declaredReturnType(methodNode: MethodNode): Option[AnalyzedReturnType] = {
    val rt = Type.getReturnType(methodNode.desc)
    if (rt.getSort == Type.OBJECT) Some(NarrowedReturnType(rt.getInternalName))
    else None
  }

  private def valueToAnalyzedType(value: BasicValue): Option[AnalyzedReturnType] = value match {
    case ev: ExactTypeValue    => Some(ExactReturnType(ev.getType.getInternalName))
    case nv: NarrowedTypeValue => Some(NarrowedReturnType(nv.getType.getInternalName))
    case _                     => None
  }

  /**
   * Determine the field values of a Scala object (singleton) by analyzing its constructor chain.
   * For example, for `object Seq extends SeqFactory.Delegate[Seq](List)`, this determines that
   * the `delegate` field holds `List$`.
   */
  def singletonFieldValues(className: String): Map[(String, String), AnalyzedReturnType] = {
    fieldValueCache.getOrElseUpdate(className, {
      if (inProgressFieldValues.contains(className)) Map.empty
      else {
        inProgressFieldValues += className
        try computeSingletonFieldValues(className)
        finally inProgressFieldValues -= className
      }
    })
  }

  private def computeSingletonFieldValues(className: String): Map[(String, String), AnalyzedReturnType] = {
    if (!className.endsWith("$")) return Map.empty

    byteCodeRepository.classNode(className) match {
      case Right(classNode) =>
        val inits = classNode.methods.asScala.filter(_.name == INSTANCE_CONSTRUCTOR_NAME)
        if (inits.isEmpty) return Map.empty
        val initMethod = inits.find(_.desc == "()V").getOrElse(inits.head)
        val initParams = Map(0 -> new ExactTypeValue(Type.getObjectType(className)).asInstanceOf[BasicValue])
        analyzeConstructorFields(className, initMethod, initParams, 3)
      case Left(_) => Map.empty
    }
  }

  /**
   * Analyze a constructor to determine what values are assigned to fields.
   * Traces through super constructor calls to find field assignments in parent constructors.
   */
  private[analysis] def analyzeConstructorFields(
      ownerClass: String,
      initMethod: MethodNode,
      paramValues: Map[Int, BasicValue],
      depth: Int
  ): Map[(String, String), AnalyzedReturnType] = {
    if (depth <= 0 || !AsmAnalyzer.sizeOKForBasicValue(initMethod)) return Map.empty

    val interpreter = new ParameterAwareInterpreter(paramValues)
    val analyzer = new Analyzer(interpreter)
    val result = mutable.Map.empty[(String, String), AnalyzedReturnType]

    try {
      val frames = analyzer.analyze(ownerClass, initMethod)
      val insns = initMethod.instructions.toArray

      for (i <- insns.indices if insns(i) != null) {
        insns(i) match {
          case fi: FieldInsnNode if fi.getOpcode == Opcodes.PUTFIELD =>
            val frame = frames(i)
            if (frame != null) {
              // Stack: ..., objectref, value → PUTFIELD
              val value = frame.getStack(frame.getStackSize - 1)
              valueToAnalyzedType(value).foreach { at =>
                result += ((fi.name, fi.desc) -> at)
              }
            }

          case mi: MethodInsnNode
              if mi.getOpcode == Opcodes.INVOKESPECIAL &&
                mi.name == INSTANCE_CONSTRUCTOR_NAME &&
                mi.owner != ownerClass &&
                depth > 1 =>
            // Super constructor call — trace arguments and recurse
            val frame = frames(i)
            if (frame != null) {
              val argTypes = Type.getArgumentTypes(mi.desc)
              val numArgs = argTypes.length
              val superParams = mutable.Map.empty[Int, BasicValue]

              // Stack: ..., objectref, arg0, ..., argN-1
              for (k <- 0 until numArgs) {
                val stackValue = frame.getStack(frame.getStackSize - numArgs + k)
                if (stackValue.isInstanceOf[ExactTypeValue] || stackValue.isInstanceOf[NarrowedTypeValue]) {
                  superParams += ((k + 1) -> stackValue) // k+1: local 0 is 'this' in callee
                }
              }
              // Pass 'this' (objectref)
              val thisValue = frame.getStack(frame.getStackSize - numArgs - 1)
              if (thisValue.isInstanceOf[ExactTypeValue]) {
                superParams += (0 -> thisValue)
              }

              byteCodeRepository.methodNode(mi.owner, INSTANCE_CONSTRUCTOR_NAME, mi.desc) match {
                case Right((superInit, _)) =>
                  result ++= analyzeConstructorFields(mi.owner, superInit, superParams.toMap, depth - 1)
                case Left(_) =>
              }
            }

          case _ =>
        }
      }
    } catch {
      case _: Exception => // analysis failed, return what we have
    }

    result.toMap
  }
}

/**
 * Type flow interpreter enhanced with interprocedural analysis.
 * Overrides GETFIELD on known-type receivers to resolve singleton field values,
 * and method calls on known-type receivers to determine precise return types.
 */
class InterproceduralTypeFlowInterpreter(
    analyzer: InterproceduralTypeAnalyzer,
    contextReceiverType: String, // null for caller-level analysis
    depth: Int
) extends NonLubbingTypeFlowInterpreter {

  override def newParameterValue(isInstanceMethod: Boolean, local: Int, tpe: Type): BasicValue = {
    if (isInstanceMethod && local == 0 && contextReceiverType != null)
      new ExactTypeValue(Type.getObjectType(contextReceiverType))
    else
      super.newParameterValue(isInstanceMethod, local, tpe)
  }

  override def unaryOperation(insn: AbstractInsnNode, value: BasicValue): BasicValue =
    insn.getOpcode match {
      case Opcodes.GETFIELD if depth > 0 =>
        val fi = insn.asInstanceOf[FieldInsnNode]
        def lookupField(typeName: String): Option[BasicValue] = {
          analyzer.singletonFieldValues(typeName).get((fi.name, fi.desc)).map {
            case ExactReturnType(name)    => new InterproceduralExactTypeValue(Type.getObjectType(name))
            case NarrowedReturnType(name) => new NarrowedTypeValue(Type.getObjectType(name))
          }
        }
        value match {
          case ev: ExactTypeValue =>
            lookupField(ev.getType.getInternalName).getOrElse(super.unaryOperation(insn, value))
          case nv: NarrowedTypeValue =>
            lookupField(nv.getType.getInternalName).getOrElse(super.unaryOperation(insn, value))
          case _ => super.unaryOperation(insn, value)
        }
      case _ => super.unaryOperation(insn, value)
    }

  override def naryOperation(insn: AbstractInsnNode, values: java.util.List[_ <: BasicValue]): BasicValue = {
    val base = super.naryOperation(insn, values)
    insn.getOpcode match {
      case Opcodes.INVOKEVIRTUAL | Opcodes.INVOKEINTERFACE | Opcodes.INVOKESPECIAL if depth > 0 =>
        val mi = insn.asInstanceOf[MethodInsnNode]
        if (mi.name == INSTANCE_CONSTRUCTOR_NAME) return base
        val receiver = values.get(0)
        receiver match {
          case ev: ExactTypeValue =>
            analyzer.analyzeReturnType(ev.getType.getInternalName, mi.name, mi.desc, depth) match {
              case Some(ExactReturnType(name))    => new InterproceduralExactTypeValue(Type.getObjectType(name))
              case Some(NarrowedReturnType(name)) => new NarrowedTypeValue(Type.getObjectType(name))
              case None                           => base
            }
          case nv: NarrowedTypeValue =>
            // Also try interprocedural analysis for narrowed receivers — the return type
            // may still be determinable (e.g., package$.Seq() returns NarrowedType(Seq$),
            // but Seq$.newBuilder() can still be analyzed to determine it returns ListBuffer)
            analyzer.analyzeReturnType(nv.getType.getInternalName, mi.name, mi.desc, depth) match {
              case Some(ExactReturnType(name))    => new InterproceduralExactTypeValue(Type.getObjectType(name))
              case Some(NarrowedReturnType(name)) => new NarrowedTypeValue(Type.getObjectType(name))
              case None                           => base
            }
          case _ => base
        }
      case _ => base
    }
  }
}

/**
 * Interpreter that injects known parameter values. Used for constructor analysis
 * where we know what arguments were passed from the caller.
 */
private[analysis] class ParameterAwareInterpreter(
    paramValues: Map[Int, BasicValue]
) extends NonLubbingTypeFlowInterpreter {

  override def newParameterValue(isInstanceMethod: Boolean, local: Int, tpe: Type): BasicValue = {
    paramValues.getOrElse(local, super.newParameterValue(isInstanceMethod, local, tpe))
  }
}
