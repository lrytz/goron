/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron

import goron.optimizer.BTypes.{InlineInfo, MethodInlineInfo}
import goron.optimizer.opt.InlineInfoAttribute

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.tools.asm.Opcodes
import scala.tools.asm.tree.{ClassNode, MethodNode}

/** Closed-world class hierarchy analysis. When the full classpath is available, we can determine:
  *   - Which classes have no subclasses (effectively final)
  *   - Which methods have no overrides (effectively final)
  *   - Monomorphic call sites for devirtualization
  */
object ClosedWorldAnalysis {

  case class ClosedWorldResult(
      /** Set of classes that are effectively final (no subclasses) */
      effectivelyFinalClasses: Set[String],
      /** Set of (owner, name, desc) for methods that are effectively final */
      effectivelyFinalMethods: Set[(String, String, String)],
      /** For abstract/interface methods with exactly one concrete implementation:
        * (abstractOwner, name, desc) → concreteImplClass.
        * Enables devirtualization of calls to abstract methods.
        */
      singleImplAbstractMethods: Map[(String, String, String), String] = Map.empty
  )

  /** Analyze the class hierarchy to determine effectively-final classes and methods. External classes (JDK, etc.) are
    * treated as having unknown subclasses.
    */
  def buildHierarchy(hierarchy: ClassHierarchy): ClosedWorldResult = {
    val classByName = hierarchy.classByName
    val subclasses = hierarchy.subclasses

    // A class is effectively final if:
    // 1. It's in our classpath (we know all its subclasses)
    // 2. It has no subclasses
    // 3. OR it's already marked final
    val effectivelyFinalClasses = classByName.collect {
      case (name, cn) if isFinalClass(cn) || !subclasses.contains(name) =>
        name
    }.toSet

    // A method is effectively final if:
    // 1. The class is effectively final, OR
    // 2. No subclass overrides it
    val effectivelyFinalMethods = mutable.Set.empty[(String, String, String)]
    for ((name, cn) <- classByName) {
      if (cn.methods != null) {
        cn.methods.asScala.foreach { mn =>
          if (isEffectivelyFinalMethod(mn, cn, name, classByName, subclasses, effectivelyFinalClasses)) {
            effectivelyFinalMethods += ((name, mn.name, mn.desc))
          }
        }
      }
    }

    // For abstract methods, find cases where there's exactly one concrete
    // implementation in the entire subclass hierarchy. This enables
    // devirtualization: calls to the abstract method can be resolved to
    // the single concrete implementation.
    val singleImplAbstractMethods = mutable.Map.empty[(String, String, String), String]
    for ((name, cn) <- classByName if cn.methods != null) {
      cn.methods.asScala.foreach { mn =>
        if (isAbstractMethod(mn) && !isStaticMethod(mn) && !isPrivateMethod(mn)) {
          val key = (name, mn.name, mn.desc)
          findSingleConcreteImpl(name, mn.name, mn.desc, classByName, subclasses) match {
            case Some(implClass) => singleImplAbstractMethods += key -> implClass
            case None            =>
          }
        }
      }
    }

    ClosedWorldResult(effectivelyFinalClasses, effectivelyFinalMethods.toSet, singleImplAbstractMethods.toMap)
  }

  private def isFinalClass(cn: ClassNode): Boolean =
    (cn.access & Opcodes.ACC_FINAL) != 0

  private def isFinalMethod(mn: MethodNode): Boolean =
    (mn.access & Opcodes.ACC_FINAL) != 0

  private def isPrivateMethod(mn: MethodNode): Boolean =
    (mn.access & Opcodes.ACC_PRIVATE) != 0

  private def isStaticMethod(mn: MethodNode): Boolean =
    (mn.access & Opcodes.ACC_STATIC) != 0

  private def isEffectivelyFinalMethod(
      mn: MethodNode,
      cn: ClassNode,
      className: String,
      classByName: Map[String, ClassNode],
      subclasses: Map[String, Set[String]],
      effectivelyFinalClasses: Set[String]
  ): Boolean = {
    // Static, private, and constructor methods are always effectively final
    if (isStaticMethod(mn) || isPrivateMethod(mn) || mn.name == "<init>" || mn.name == "<clinit>") return true
    // Already final
    if (isFinalMethod(mn) || isFinalClass(cn)) return true
    // Class is effectively final (no subclasses)
    if (effectivelyFinalClasses.contains(className)) return true

    // Check if any transitive subclass overrides this method
    !hasOverrideInSubclasses(className, mn.name, mn.desc, classByName, subclasses)
  }

  private def isAbstractMethod(mn: MethodNode): Boolean =
    (mn.access & Opcodes.ACC_ABSTRACT) != 0

  /** Find the single concrete implementation of an abstract method in the subclass hierarchy.
    * Returns Some(className) if exactly one class provides a concrete implementation
    * and no subclass of that class overrides it. Returns None otherwise.
    */
  private def findSingleConcreteImpl(
      className: String,
      methodName: String,
      methodDesc: String,
      classByName: Map[String, ClassNode],
      subclasses: Map[String, Set[String]]
  ): Option[String] = {
    val impls = mutable.Set.empty[String]
    collectConcreteImpls(className, methodName, methodDesc, classByName, subclasses, impls)
    if (impls.size == 1) Some(impls.head) else None
  }

  /** Collect all classes in the transitive subclass hierarchy that provide a concrete
    * implementation of the given method (name, desc). Stops collecting early if more
    * than one implementation is found.
    */
  private def collectConcreteImpls(
      className: String,
      methodName: String,
      methodDesc: String,
      classByName: Map[String, ClassNode],
      subclasses: Map[String, Set[String]],
      result: mutable.Set[String]
  ): Unit = {
    if (result.size > 1) return
    for (sub <- subclasses.getOrElse(className, Set.empty)) {
      classByName.get(sub).foreach { subNode =>
        val hasImpl = subNode.methods.asScala.exists { m =>
          m.name == methodName && m.desc == methodDesc && !isAbstractMethod(m)
        }
        if (hasImpl) {
          result += sub
          // Still recurse: a further subclass might override with a different impl
          collectConcreteImpls(sub, methodName, methodDesc, classByName, subclasses, result)
        } else {
          // No impl here, keep searching deeper
          collectConcreteImpls(sub, methodName, methodDesc, classByName, subclasses, result)
        }
      }
    }
  }

  private def hasOverrideInSubclasses(
      className: String,
      methodName: String,
      methodDesc: String,
      classByName: Map[String, ClassNode],
      subclasses: Map[String, Set[String]]
  ): Boolean = {
    val directSubs = subclasses.getOrElse(className, Set.empty)
    directSubs.exists { sub =>
      val subNode = classByName.get(sub)
      val hasOverride = subNode.exists(_.methods.asScala.exists(m => m.name == methodName && m.desc == methodDesc))
      hasOverride || hasOverrideInSubclasses(sub, methodName, methodDesc, classByName, subclasses)
    }
  }

  /** Apply closed-world knowledge to ClassNodes by updating ACC_FINAL flags and ScalaInlineInfo attributes. This marks
    * effectively-final classes and methods so the inliner can be more aggressive.
    *
    * ACC_FINAL is only set on leaf classes (setting it on non-leaf classes causes ClassFormatErrors). For the inliner,
    * which uses ScalaInlineInfo.effectivelyFinal rather than ACC_FINAL, we update the InlineInfoAttribute directly on
    * ALL classes. This allows the inliner to exploit effectively-final methods on non-leaf Scala classes.
    */
  def applyToClassNodes(classNodes: Iterable[ClassNode], hierarchy: ClosedWorldResult): Unit = {
    for (cn <- classNodes) {
      val isLeafClass = hierarchy.effectivelyFinalClasses.contains(cn.name)
      val isInterface = (cn.access & Opcodes.ACC_INTERFACE) != 0

      // ACC_FINAL: only on leaf, non-interface classes
      if (isLeafClass && !isFinalClass(cn) && !isInterface && cn.methods != null) {
        cn.methods.asScala.foreach { mn =>
          val isAbstract = (mn.access & Opcodes.ACC_ABSTRACT) != 0
          if (
            !isFinalMethod(mn) && !isStaticMethod(mn) && !isPrivateMethod(mn)
            && !isAbstract && mn.name != "<init>" && mn.name != "<clinit>"
          ) {
            mn.access |= Opcodes.ACC_FINAL
          }
        }
      }

      // ScalaInlineInfo: update the attribute to reflect closed-world effectively-final knowledge.
      // This is how the inliner learns about effectively-final methods on non-leaf classes.
      updateInlineInfoAttribute(cn, hierarchy)
    }
  }

  /** Update the ScalaInlineInfo attribute on a ClassNode to include closed-world effectively-final knowledge. If no
    * attribute exists (non-Scala class), this is a no-op — the inliner will fall back to ACC_FINAL checks.
    */
  private def updateInlineInfoAttribute(cn: ClassNode, hierarchy: ClosedWorldResult): Unit = {
    if (cn.attrs == null) return
    val attrIndex = cn.attrs.asScala.indexWhere(_.isInstanceOf[InlineInfoAttribute])
    if (attrIndex < 0) return

    val existing = cn.attrs.get(attrIndex).asInstanceOf[InlineInfoAttribute].inlineInfo
    val isClassFinal = existing.isEffectivelyFinal || hierarchy.effectivelyFinalClasses.contains(cn.name)

    var changed = isClassFinal != existing.isEffectivelyFinal

    val updatedMethodInfos = existing.methodInfos.map { case (key @ (name, desc), info) =>
      if (!info.effectivelyFinal && hierarchy.effectivelyFinalMethods.contains((cn.name, name, desc))) {
        changed = true
        key -> info.copy(effectivelyFinal = true)
      } else {
        key -> info
      }
    }

    if (changed) {
      val updatedInfo = existing.copy(isEffectivelyFinal = isClassFinal, methodInfos = updatedMethodInfos)
      cn.attrs.set(attrIndex, InlineInfoAttribute(updatedInfo))
    }
  }

}
