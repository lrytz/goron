/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron.optimizer.opt

import goron.optimizer.BTypes.InternalName
import goron.optimizer.BackendReporting._
import goron.optimizer.Position.NoPosition
import goron.optimizer.analysis.BackendUtils.LambdaMetaFactoryCall
import goron.optimizer.opt.BytecodeUtils._
import goron.optimizer.{PerRunInit, PostProcessor}

import scala.annotation.nowarn
import scala.collection.{concurrent, mutable}
import scala.jdk.CollectionConverters._
import scala.tools.asm
import scala.tools.asm.tree._
import scala.tools.asm.{Attribute, Type}

/** The ByteCodeRepository provides utilities to read the bytecode of classfiles from the compilation classpath. Parsed
  * classes are cached in the `classes` map.
  */
abstract class ByteCodeRepository extends PerRunInit {
  val postProcessor: PostProcessor

  import postProcessor.{bTypes, bTypesFromClassfile}
  import bTypes._

  def backendReporting: Reporter = bTypes.backendReporting
  def classpath: goron.Classpath = bTypes.classpath

  /** Contains ClassNodes and the canonical path of the source file path of classes being compiled in the current
    * compilation run.
    */
  val compilingClasses: concurrent.Map[InternalName, (ClassNode, String)] = concurrent.TrieMap.empty

  // For link-time optimization, we don't need a cache limit — we want all classes in memory.
  // Use an unbounded concurrent map instead of the original LRU cache.
  val parsedClasses: mutable.Map[InternalName, Either[ClassNotFound, ClassNode]] =
    new java.util.concurrent.ConcurrentHashMap[InternalName, Either[ClassNotFound, ClassNode]]().asScala

  def add(classNode: ClassNode, sourceFilePath: Option[String]) = sourceFilePath match {
    case Some(path) if path != "<no file>" => compilingClasses(classNode.name) = (classNode, path)
    case _                                 => parsedClasses(classNode.name) = Right(classNode)
  }

  private def parsedClassNode(internalName: InternalName): Either[ClassNotFound, ClassNode] = {
    parsedClasses.getOrElseUpdate(internalName, parseClass(internalName))
  }

  def classNodeAndSourceFilePath(internalName: InternalName): Either[ClassNotFound, (ClassNode, Option[String])] = {
    compilingClasses.get(internalName) match {
      case Some((c, p)) => Right((c, Some(p)))
      case _            => parsedClassNode(internalName).map((_, None))
    }
  }

  def classNode(internalName: InternalName): Either[ClassNotFound, ClassNode] = {
    compilingClasses.get(internalName) match {
      case Some((c, _)) => Right(c)
      case None         => parsedClassNode(internalName)
    }
  }

  def fieldNode(
      classInternalName: InternalName,
      name: String,
      descriptor: String
  ): Either[FieldNotFound, (FieldNode, InternalName)] = {
    def fieldNodeImpl(parent: InternalName): Either[FieldNotFound, (FieldNode, InternalName)] = {
      classNode(parent) match {
        case Left(e)  => Left(FieldNotFound(name, descriptor, classInternalName, Some(e)))
        case Right(c) =>
          c.fields.asScala.find(f => f.name == name && f.desc == descriptor) match {
            case Some(f) => Right((f, parent))
            case None    =>
              if (c.superName == null) Left(FieldNotFound(name, descriptor, classInternalName, None))
              else fieldNode(c.superName, name, descriptor)
          }
      }
    }
    fieldNodeImpl(classInternalName)
  }

  def methodNode(
      ownerInternalNameOrArrayDescriptor: String,
      name: String,
      descriptor: String
  ): Either[MethodNotFound, (MethodNode, InternalName)] = {
    def findMethod(c: ClassNode): Option[MethodNode] =
      c.methods.asScala.find(m => m.name == name && m.desc == descriptor)

    def findSignaturePolymorphic(owner: ClassNode): Option[MethodNode] = {
      def hasObjectArrayParam(m: MethodNode) = Type.getArgumentTypes(m.desc) match {
        case Array(pt) =>
          pt.getDimensions == 1 && pt.getElementType.getInternalName == coreBTypes.ObjectRef.internalName
        case _ => false
      }
      if (owner.name == coreBTypes.jliMethodHandleRef.internalName || owner.name == "java/lang/invoke/VarHandle")
        owner.methods.asScala.find(m =>
          m.name == name &&
            isNativeMethod(m) &&
            isVarargsMethod(m) &&
            hasObjectArrayParam(m)
        )
      else None
    }

    def findInSuperClasses(
        owner: ClassNode,
        publicInstanceOnly: Boolean = false
    ): Either[ClassNotFound, Option[(MethodNode, InternalName)]] = {
      findMethod(owner) match {
        case Some(m) if !publicInstanceOnly || (isPublicMethod(m) && !isStaticMethod(m)) => Right(Some((m, owner.name)))
        case _                                                                           =>
          findSignaturePolymorphic(owner) match {
            case Some(m) => Right(Some((m, owner.name)))
            case _       =>
              if (owner.superName == null) Right(None)
              else classNode(owner.superName).flatMap(findInSuperClasses(_, publicInstanceOnly = isInterface(owner)))
          }
      }
    }

    def findInInterfaces(initialOwner: ClassNode): Either[ClassNotFound, Option[(MethodNode, InternalName)]] = {
      val visited = mutable.Set.empty[InternalName]
      val found = mutable.ListBuffer.empty[(MethodNode, ClassNode)]

      @nowarn("cat=lint-nonlocal-return")
      def findIn(owner: ClassNode): Option[ClassNotFound] = {
        for (i <- owner.interfaces.asScala if !visited(i)) classNode(i) match {
          case Left(e)  => return Some(e)
          case Right(c) =>
            visited += i
            for (m <- findMethod(c) if !isPrivateMethod(m) && !isStaticMethod(m)) found += ((m, c))
            val recursionResult = findIn(c)
            if (recursionResult.isDefined) return recursionResult
        }
        None
      }

      findIn(initialOwner) match {
        case Some(cnf) => Left(cnf)
        case _         =>
          val result =
            if (found.sizeIs <= 1) found.headOption
            else {
              val maxSpecific = found.filterNot { case (method, owner) =>
                val ownerTp = bTypesFromClassfile.classBTypeFromClassNode(owner)
                found.exists { case (other, otherOwner) =>
                  (other ne method) && {
                    val otherTp = bTypesFromClassfile.classBTypeFromClassNode(otherOwner)
                    otherTp.isSubtypeOf(ownerTp).get
                  }
                }
              }
              val nonAbs = maxSpecific.filterNot(p => isAbstractMethod(p._1))
              if (nonAbs.sizeIs == 1) nonAbs.headOption
              else {
                val foundNonAbs = found.filterNot(p => isAbstractMethod(p._1))
                if (foundNonAbs.sizeIs == 1) foundNonAbs.headOption
                else if (foundNonAbs.isEmpty) found.headOption
                else None
              }
            }
          Right(result.map(p => (p._1, p._2.name)))
      }
    }

    if (ownerInternalNameOrArrayDescriptor.charAt(0) == '[') {
      Left(MethodNotFound(name, descriptor, ownerInternalNameOrArrayDescriptor, None))
    } else {
      def notFound(cnf: Option[ClassNotFound]) = Left(
        MethodNotFound(name, descriptor, ownerInternalNameOrArrayDescriptor, cnf)
      )
      val res: Either[ClassNotFound, Option[(MethodNode, InternalName)]] =
        classNode(ownerInternalNameOrArrayDescriptor).flatMap(c =>
          findInSuperClasses(c) flatMap {
            case None => findInInterfaces(c)
            case res  => Right(res)
          }
        )
      res match {
        case Left(e)          => notFound(Some(e))
        case Right(None)      => notFound(None)
        case Right(Some(res)) => Right(res)
      }
    }
  }

  private def removeLineNumbersAndAddLMFImplMethods(classNode: ClassNode): Unit = {
    for (m <- classNode.methods.asScala) {
      val iter = m.instructions.iterator
      while (iter.hasNext) {
        val insn = iter.next()
        insn.getType match {
          case AbstractInsnNode.LINE =>
            iter.remove()
          case AbstractInsnNode.INVOKE_DYNAMIC_INSN =>
            insn match {
              case LambdaMetaFactoryCall(indy, _, implMethod, _, _) =>
                postProcessor.backendUtils.addIndyLambdaImplMethod(classNode.name, m, indy, implMethod)
              case _ =>
            }
          case _ =>
        }
      }
    }
  }

  private def parseClass(internalName: InternalName): Either[ClassNotFound, ClassNode] = {
    classpath.findClassBytes(internalName) match {
      case Some(bytes) =>
        val classNode = new goron.optimizer.ClassNode1()
        val classReader = new asm.ClassReader(bytes)
        try {
          classReader.accept(classNode, Array[Attribute](InlineInfoAttributePrototype), asm.ClassReader.SKIP_FRAMES)
          removeLineNumbersAndAddLMFImplMethods(classNode)
          Right(classNode)
        } catch {
          case ex: Exception =>
            backendReporting.warning(NoPosition, s"Error while reading classfile for $internalName\n${ex.getMessage}")
            Left(ClassNotFound(internalName, definedInJavaSource = false))
        }
      case None =>
        Left(ClassNotFound(internalName, definedInJavaSource = false))
    }
  }
}
