package goron

import utest._
import java.io.File

object GoronTest extends TestSuite {
  /** Generate a minimal valid classfile using ASM */
  private def minimalClassBytes(internalName: String): Array[Byte] = {
    import scala.tools.asm._
    val cw = new ClassWriter(0)
    cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
    cw.visitEnd()
    cw.toByteArray
  }

  val tests = Tests {
    test("JarIO roundtrip") {
      val tmpIn = File.createTempFile("goron-test-in", ".jar")
      val tmpOut = File.createTempFile("goron-test-out", ".jar")
      tmpIn.deleteOnExit()
      tmpOut.deleteOnExit()

      val classBytes = minimalClassBytes("com/example/Foo")
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

      // Roundtrip through Goron (with optimizations disabled for this simple test)
      Goron.run(GoronConfig(
        inputJars = List(tmpIn.getAbsolutePath),
        outputJar = tmpOut.getAbsolutePath,
        optInlinerEnabled = false,
        optClosureInvocations = false,
        optLocalOptimizations = false,
      ))

      val roundtripped = JarIO.readJar(tmpOut.getAbsolutePath)
      assert(roundtripped.size == 2)
      assert(roundtripped.exists(_.name == "com/example/Foo.class"))
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

    test("Local optimizations roundtrip") {
      val tmpIn = File.createTempFile("goron-test-opt-in", ".jar")
      val tmpOut = File.createTempFile("goron-test-opt-out", ".jar")
      tmpIn.deleteOnExit()
      tmpOut.deleteOnExit()

      // Create a jar with a class that has a method
      val classBytes = {
        import scala.tools.asm._
        val cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "com/example/Bar", null, "java/lang/Object", null)
        // Add a simple method: public static int add(int a, int b) { return a + b; }
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "add", "(II)I", null, null)
        mv.visitCode()
        mv.visitVarInsn(Opcodes.ILOAD, 0)
        mv.visitVarInsn(Opcodes.ILOAD, 1)
        mv.visitInsn(Opcodes.IADD)
        mv.visitInsn(Opcodes.IRETURN)
        mv.visitMaxs(2, 2)
        mv.visitEnd()
        cw.visitEnd()
        cw.toByteArray
      }

      val entries = Seq(JarIO.JarEntry("com/example/Bar.class", classBytes, isClass = true))
      JarIO.writeJar(tmpIn.getAbsolutePath, entries)

      // Run with local optimizations enabled
      Goron.run(GoronConfig(
        inputJars = List(tmpIn.getAbsolutePath),
        outputJar = tmpOut.getAbsolutePath,
        optInlinerEnabled = false,
        optClosureInvocations = false,
        optLocalOptimizations = true,
      ))

      // Verify the output is a valid classfile
      val result = JarIO.readJar(tmpOut.getAbsolutePath)
      assert(result.size == 1)
      assert(result.head.name == "com/example/Bar.class")
      // Verify it's a valid classfile by parsing it
      val cn = new scala.tools.asm.tree.ClassNode()
      new scala.tools.asm.ClassReader(result.head.bytes).accept(cn, 0)
      assert(cn.name == "com/example/Bar")
      assert(cn.methods.size() == 1)
    }
  }
}
