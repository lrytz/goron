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
