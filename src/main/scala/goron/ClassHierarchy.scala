/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.tools.asm.tree.ClassNode

/** Shared class hierarchy data structure built once from a set of ClassNodes and reused across
  * reachability analysis, closed-world analysis, and the optimizer pipeline.
  *
  * @param classByName
  *   Map from class internal name to its ClassNode
  * @param subclasses
  *   Map from class internal name to its direct subclasses (both from extends and implements)
  */
case class ClassHierarchy(
    classByName: Map[String, ClassNode],
    subclasses: Map[String, Set[String]]
)

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

    ClassHierarchy(classByName, subclasses.view.mapValues(_.toSet).toMap)
  }
}
