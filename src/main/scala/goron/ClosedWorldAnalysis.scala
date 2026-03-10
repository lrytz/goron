/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron

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
      effectivelyFinalMethods: Set[(String, String, String)]
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

    ClosedWorldResult(effectivelyFinalClasses, effectivelyFinalMethods.toSet)
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

  /** Apply closed-world knowledge to ClassNodes by updating InlineInfo. This marks effectively-final classes and
    * methods so the inliner can be more aggressive.
    */
  def applyToClassNodes(classNodes: Iterable[ClassNode], hierarchy: ClosedWorldResult): Unit = {
    // Only mark methods ACC_FINAL in classes that are themselves effectively final (leaf classes).
    // Marking methods final in non-leaf classes can cause ClassFormatErrors when methods
    // override abstract methods from interfaces/traits.
    for (cn <- classNodes) {
      val isLeafClass = hierarchy.effectivelyFinalClasses.contains(cn.name)
      val isInterface = (cn.access & Opcodes.ACC_INTERFACE) != 0
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
    }
  }

}
