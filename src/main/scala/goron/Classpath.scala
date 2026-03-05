package goron

trait Classpath {
  def findClassBytes(internalName: String): Option[Array[Byte]]
  def classNames: Set[String]
}

class JarClasspath(entries: Map[String, Array[Byte]]) extends Classpath {
  def findClassBytes(internalName: String): Option[Array[Byte]] =
    entries.get(internalName)

  def classNames: Set[String] = entries.keySet
}

object JarClasspath {
  def fromClassEntries(entries: Seq[(String, Array[Byte])]): JarClasspath = {
    val map = entries.map { case (name, bytes) =>
      val internalName = if (name.endsWith(".class")) name.dropRight(6) else name
      internalName -> bytes
    }.toMap
    new JarClasspath(map)
  }
}
