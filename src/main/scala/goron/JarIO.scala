package goron

import java.io._
import java.util.jar._
import java.util.zip._
import scala.collection.mutable

object JarIO {
  case class JarEntry(name: String, bytes: Array[Byte], isClass: Boolean)

  def readJar(path: String): Seq[JarEntry] = {
    val entries = mutable.ArrayBuffer.empty[JarEntry]
    val jis = new JarInputStream(new BufferedInputStream(new FileInputStream(path)))
    try {
      var entry = jis.getNextJarEntry
      while (entry != null) {
        if (!entry.isDirectory) {
          val bytes = readBytes(jis)
          val isClass = entry.getName.endsWith(".class")
          entries += JarEntry(entry.getName, bytes, isClass)
        }
        entry = jis.getNextJarEntry
      }
    } finally {
      jis.close()
    }
    entries.toSeq
  }

  def readJars(paths: List[String]): Seq[JarEntry] =
    paths.flatMap(readJar)

  def writeJar(path: String, entries: Seq[JarEntry], manifest: Option[Manifest] = None): Unit = {
    val man = manifest.getOrElse(new Manifest())
    man.getMainAttributes.putIfAbsent(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0")
    val jos = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(path)), man)
    try {
      val written = mutable.Set.empty[String]
      for (entry <- entries if written.add(entry.name)) {
        jos.putNextEntry(new ZipEntry(entry.name))
        jos.write(entry.bytes)
        jos.closeEntry()
      }
    } finally {
      jos.close()
    }
  }

  private def readBytes(is: InputStream): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val buf = new Array[Byte](8192)
    var n = is.read(buf)
    while (n != -1) {
      baos.write(buf, 0, n)
      n = is.read(buf)
    }
    baos.toByteArray
  }
}
