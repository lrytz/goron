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

/**
 * Classpath that falls back to loading classes from the JDK runtime.
 * This is needed because the optimizer resolves well-known types like java/lang/Object,
 * java/lang/Character, etc. that are part of the JDK, not the user's jars.
 */
class RuntimeClasspath(primary: Classpath) extends Classpath {
  def findClassBytes(internalName: String): Option[Array[Byte]] =
    primary.findClassBytes(internalName).orElse(loadFromRuntime(internalName))

  def classNames: Set[String] = primary.classNames

  private def loadFromRuntime(internalName: String): Option[Array[Byte]] = {
    val resourceName = internalName + ".class"
    val cl = ClassLoader.getSystemClassLoader
    val stream = cl.getResourceAsStream(resourceName)
    if (stream == null) None
    else {
      try {
        val bytes = stream.readAllBytes()
        Some(bytes)
      } finally {
        stream.close()
      }
    }
  }
}
