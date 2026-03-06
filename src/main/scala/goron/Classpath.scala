/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

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

/** Classpath that falls back to loading classes from a classloader. This is needed because the optimizer resolves
  * well-known types like java/lang/Object, java/lang/Character, etc. that are part of the JDK or scala-library.
  *
  * @param classLoader
  *   The classloader to use for fallback lookups. Defaults to the system classloader, which works when running as a
  *   standalone JVM process. In environments like sbt's in-process test runner, pass the test classloader to also find
  *   scala-library classes.
  */
class RuntimeClasspath(primary: Classpath, classLoader: ClassLoader = ClassLoader.getSystemClassLoader)
    extends Classpath {
  def findClassBytes(internalName: String): Option[Array[Byte]] =
    primary.findClassBytes(internalName).orElse(loadFromRuntime(internalName))

  def classNames: Set[String] = primary.classNames

  private def loadFromRuntime(internalName: String): Option[Array[Byte]] = {
    val resourceName = internalName + ".class"
    val stream = classLoader.getResourceAsStream(resourceName)
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
