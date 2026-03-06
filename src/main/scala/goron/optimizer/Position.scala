/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron.optimizer

sealed trait Position {
  def source: Position.SourceFile
}

object Position {
  case class SourceFile(path: String, name: String)
  val NoSourceFile: SourceFile = SourceFile("<no file>", "<no file>")

  case object NoPosition extends Position {
    val source: SourceFile = NoSourceFile
  }

  case class Simple(message: String) extends Position {
    val source: SourceFile = NoSourceFile
  }

  def apply(msg: String): Position = Simple(msg)
}
