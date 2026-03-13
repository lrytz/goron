/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.tools.asm.tree.{ClassNode, MethodNode}

/** Shared class hierarchy data structure built once from a set of ClassNodes and reused across
  * reachability analysis, closed-world analysis, and the optimizer pipeline.
  *
  * @param classByName
  *   Map from class internal name to its ClassNode
  * @param subclasses
  *   Map from class internal name to its direct subclasses (both from extends and implements)
  * @param transitiveSupertypes
  *   Map from class internal name to all its transitive supertypes (including itself)
  * @param methodIndex
  *   Map from class internal name to its methods keyed by (name, desc)
  */
case class ClassHierarchy(
    classByName: Map[String, ClassNode],
    subclasses: Map[String, Set[String]],
    transitiveSupertypes: Map[String, Set[String]],
    methodIndex: Map[String, Map[(String, String), MethodNode]]
) {

  /** O(1) subtype check using precomputed transitive supertypes. */
  def isSubclassOf(child: String, parent: String): Boolean =
    transitiveSupertypes.getOrElse(child, Set.empty).contains(parent)

  /** O(1) method lookup by class name, method name, and descriptor. */
  def lookupMethod(className: String, name: String, desc: String): Option[MethodNode] =
    methodIndex.get(className).flatMap(_.get((name, desc)))

  /** O(1) method existence check. */
  def hasMethod(className: String, name: String, desc: String): Boolean =
    methodIndex.get(className).exists(_.contains((name, desc)))

  /** Check if any transitive subclass of `className` declares a method with the given name and desc. */
  def hasOverrideInSubclasses(className: String, methodName: String, methodDesc: String): Boolean = {
    def check(cls: String): Boolean = {
      subclasses.getOrElse(cls, Set.empty).exists { sub =>
        val hasOverride = methodIndex.get(sub).exists(_.contains((methodName, methodDesc)))
        hasOverride || check(sub)
      }
    }
    check(className)
  }
}

object ClassHierarchy {

  /** Build the class hierarchy from all program classes. */
  def build(classNodes: Iterable[ClassNode]): ClassHierarchy = {
    val classByName = classNodes.map(cn => cn.name -> cn).toMap
    val subclasses = mutable.Map.empty[String, mutable.Set[String]]

    for (cn <- classNodes) {
      if (cn.superName != null)
        subclasses.getOrElseUpdate(cn.superName, mutable.Set.empty) += cn.name
      if (cn.interfaces != null)
        cn.interfaces.asScala.foreach(iface => subclasses.getOrElseUpdate(iface, mutable.Set.empty) += cn.name)
    }

    val subclassesImmutable = subclasses.view.mapValues(_.toSet).toMap

    // Precompute transitive supertypes for O(1) isSubclassOf
    val supertypeCache = mutable.Map.empty[String, Set[String]]
    def supertypesOf(name: String): Set[String] = {
      supertypeCache.getOrElseUpdate(
        name, {
          classByName.get(name) match {
            case Some(cn) =>
              var result = Set(name)
              if (cn.superName != null) result ++= supertypesOf(cn.superName)
              if (cn.interfaces != null) cn.interfaces.asScala.foreach(i => result ++= supertypesOf(i))
              result
            case None => Set(name)
          }
        }
      )
    }
    classByName.keysIterator.foreach(supertypesOf)

    // Build per-class method lookup index for O(1) method checks
    val methodIndex = classByName.map { case (name, cn) =>
      val methods =
        if (cn.methods != null)
          cn.methods.asScala.iterator.map(mn => (mn.name, mn.desc) -> mn).toMap
        else Map.empty[(String, String), MethodNode]
      name -> methods
    }

    ClassHierarchy(classByName, subclassesImmutable, supertypeCache.toMap, methodIndex)
  }
}
