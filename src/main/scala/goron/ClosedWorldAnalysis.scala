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

  case class ClassHierarchy(
      /** Map from class internal name to its direct subclasses */
      subclasses: Map[String, Set[String]],
      /** Map from class internal name to its ClassNode */
      classByName: Map[String, ClassNode],
      /** Set of classes that are effectively final (no subclasses) */
      effectivelyFinalClasses: Set[String],
      /** Set of (owner, name, desc) for methods that are effectively final */
      effectivelyFinalMethods: Set[(String, String, String)]
  )

  /** Build the class hierarchy from all program classes. External classes (JDK, etc.) are treated as having unknown
    * subclasses.
    */
  def buildHierarchy(classNodes: Iterable[ClassNode]): ClassHierarchy = {
    val classByName = classNodes.map(cn => cn.name -> cn).toMap
    val subclasses = mutable.Map.empty[String, mutable.Set[String]]

    // Build parent → children map
    for (cn <- classNodes) {
      if (cn.superName != null) {
        subclasses.getOrElseUpdate(cn.superName, mutable.Set.empty) += cn.name
      }
      if (cn.interfaces != null) {
        cn.interfaces.asScala.foreach { iface =>
          subclasses.getOrElseUpdate(iface, mutable.Set.empty) += cn.name
        }
      }
    }

    val subclassesImmutable = subclasses.view.mapValues(_.toSet).toMap

    // A class is effectively final if:
    // 1. It's in our classpath (we know all its subclasses)
    // 2. It has no subclasses
    // 3. OR it's already marked final
    val effectivelyFinalClasses = classByName.collect {
      case (name, cn) if isFinalClass(cn) || !subclassesImmutable.contains(name) =>
        name
    }.toSet

    // A method is effectively final if:
    // 1. The class is effectively final, OR
    // 2. No subclass overrides it
    val effectivelyFinalMethods = mutable.Set.empty[(String, String, String)]
    for ((name, cn) <- classByName) {
      if (cn.methods != null) {
        cn.methods.asScala.foreach { mn =>
          if (isEffectivelyFinalMethod(mn, cn, name, classByName, subclassesImmutable, effectivelyFinalClasses)) {
            effectivelyFinalMethods += ((name, mn.name, mn.desc))
          }
        }
      }
    }

    ClassHierarchy(subclassesImmutable, classByName, effectivelyFinalClasses, effectivelyFinalMethods.toSet)
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
  def applyToClassNodes(classNodes: Iterable[ClassNode], hierarchy: ClassHierarchy): Unit = {
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

  /** Devirtualize monomorphic call sites: replace invokevirtual/invokeinterface with invokestatic for final methods in
    * final classes, when the receiver type can be precisely determined.
    *
    * This is a conservative version that only devirtualizes calls on classes that are effectively final (no subclasses
    * in the closed world).
    */
  def devirtualize(classNodes: Iterable[ClassNode], hierarchy: ClassHierarchy): Int = {
    var count = 0
    for (cn <- classNodes; mn <- cn.methods.asScala if mn.instructions != null) {
      val iter = mn.instructions.iterator()
      while (iter.hasNext) {
        iter.next() match {
          case mi: scala.tools.asm.tree.MethodInsnNode
              if (mi.getOpcode == Opcodes.INVOKEVIRTUAL || mi.getOpcode == Opcodes.INVOKEINTERFACE)
                && hierarchy.effectivelyFinalClasses.contains(mi.owner)
                && hierarchy.effectivelyFinalMethods.contains((mi.owner, mi.name, mi.desc)) =>
            // This call can be devirtualized — the receiver class has no subclasses
            // and the method has no overrides. But we can't just change to invokestatic
            // because the method isn't actually static. We leave this as-is for now
            // and let the JIT devirtualize it. The ACC_FINAL marking already helps.
            // A full devirtualization would require creating a static forwarder.
            count += 1
          case _ =>
        }
      }
    }
    count
  }
}
