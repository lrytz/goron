package goron

import utest._
import java.io.File
import java.util.jar._

object GoronTest extends TestSuite {
  val tests = Tests {
    test("JarIO roundtrip") {
      val tmpIn = File.createTempFile("goron-test-in", ".jar")
      val tmpOut = File.createTempFile("goron-test-out", ".jar")
      tmpIn.deleteOnExit()
      tmpOut.deleteOnExit()

      // Create a jar with a fake class entry and a resource
      val classBytes = Array[Byte](0xCA.toByte, 0xFE.toByte, 0xBA.toByte, 0xBE.toByte, 0, 0, 0, 52)
      val resourceBytes = "hello".getBytes("UTF-8")
      val entries = Seq(
        JarIO.JarEntry("com/example/Foo.class", classBytes, isClass = true),
        JarIO.JarEntry("META-INF/services/example", resourceBytes, isClass = false),
      )
      JarIO.writeJar(tmpIn.getAbsolutePath, entries)

      // Read it back
      val read = JarIO.readJar(tmpIn.getAbsolutePath)
      assert(read.size == 2)
      assert(read.exists(e => e.name == "com/example/Foo.class" && e.isClass))
      assert(read.exists(e => e.name == "META-INF/services/example" && !e.isClass))

      // Roundtrip through Goron
      Goron.run(GoronConfig(
        inputJars = List(tmpIn.getAbsolutePath),
        outputJar = tmpOut.getAbsolutePath,
      ))

      val roundtripped = JarIO.readJar(tmpOut.getAbsolutePath)
      assert(roundtripped.size == 2)
      assert(roundtripped.find(_.name == "com/example/Foo.class").get.bytes.toSeq == classBytes.toSeq)
      assert(roundtripped.find(_.name == "META-INF/services/example").get.bytes.toSeq == resourceBytes.toSeq)
    }

    test("Classpath lookup") {
      val cp = JarClasspath.fromClassEntries(Seq(
        "com/example/Foo.class" -> Array[Byte](1, 2, 3),
        "com/example/Bar.class" -> Array[Byte](4, 5, 6),
      ))
      assert(cp.findClassBytes("com/example/Foo").isDefined)
      assert(cp.findClassBytes("com/example/Baz").isEmpty)
      assert(cp.classNames.size == 2)
    }

    test("CLI parsing") {
      val config = GoronCli.parseArgs(List("--input", "a.jar", "--input", "b.jar", "--output", "out.jar", "--verbose"))
      assert(config.isDefined)
      assert(config.get.inputJars == List("a.jar", "b.jar"))
      assert(config.get.outputJar == "out.jar")
      assert(config.get.verbose)
    }

    test("CLI missing input") {
      val config = GoronCli.parseArgs(List("--output", "out.jar"))
      assert(config.isEmpty)
    }
  }
}
