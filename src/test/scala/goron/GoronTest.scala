/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron

import java.io.File

class GoronTest extends munit.FunSuite {
  /** Generate a minimal valid classfile using ASM */
  private def minimalClassBytes(internalName: String): Array[Byte] = {
    import scala.tools.asm._
    val cw = new ClassWriter(0)
    cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
    cw.visitEnd()
    cw.toByteArray
  }

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
    assertEquals(read.size, 2)
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
    assertEquals(roundtripped.size, 2)
    assert(roundtripped.exists(_.name == "com/example/Foo.class"))
    assertEquals(
      roundtripped.find(_.name == "META-INF/services/example").get.bytes.toSeq,
      resourceBytes.toSeq,
    )
  }

  test("Classpath lookup") {
    val cp = JarClasspath.fromClassEntries(Seq(
      "com/example/Foo.class" -> Array[Byte](1, 2, 3),
      "com/example/Bar.class" -> Array[Byte](4, 5, 6),
    ))
    assert(cp.findClassBytes("com/example/Foo").isDefined)
    assert(cp.findClassBytes("com/example/Baz").isEmpty)
    assertEquals(cp.classNames.size, 2)
  }

  test("CLI parsing") {
    val config = GoronCli.parseArgs(List("--input", "a.jar", "--input", "b.jar", "--output", "out.jar", "--verbose"))
    assert(config.isDefined)
    assertEquals(config.get.inputJars, List("a.jar", "b.jar"))
    assertEquals(config.get.outputJar, "out.jar")
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

    val classBytes = {
      import scala.tools.asm._
      val cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
      cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "com/example/Bar", null, "java/lang/Object", null)
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

    Goron.run(GoronConfig(
      inputJars = List(tmpIn.getAbsolutePath),
      outputJar = tmpOut.getAbsolutePath,
      optInlinerEnabled = false,
      optClosureInvocations = false,
      optLocalOptimizations = true,
    ))

    val result = JarIO.readJar(tmpOut.getAbsolutePath)
    assertEquals(result.size, 1)
    assertEquals(result.head.name, "com/example/Bar.class")
    val cn = new scala.tools.asm.tree.ClassNode()
    new scala.tools.asm.ClassReader(result.head.bytes).accept(cn, 0)
    assertEquals(cn.name, "com/example/Bar")
    assertEquals(cn.methods.size(), 1)
  }

  test("Dead code elimination") {
    val tmpIn = File.createTempFile("goron-test-dce-in", ".jar")
    val tmpOut = File.createTempFile("goron-test-dce-out", ".jar")
    tmpIn.deleteOnExit()
    tmpOut.deleteOnExit()

    import scala.tools.asm._

    val mainBytes = {
      val cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
      cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "com/example/Main", null, "java/lang/Object", null)
      val mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null)
      mv.visitCode()
      mv.visitInsn(Opcodes.ICONST_5)
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/Used", "process", "(I)I", false)
      mv.visitInsn(Opcodes.POP)
      mv.visitInsn(Opcodes.RETURN)
      mv.visitMaxs(1, 1)
      mv.visitEnd()
      cw.visitEnd()
      cw.toByteArray
    }

    val usedBytes = minimalClassBytes("com/example/Used")
    val unusedBytes = minimalClassBytes("com/example/Unused")

    val entries = Seq(
      JarIO.JarEntry("com/example/Main.class", mainBytes, isClass = true),
      JarIO.JarEntry("com/example/Used.class", usedBytes, isClass = true),
      JarIO.JarEntry("com/example/Unused.class", unusedBytes, isClass = true),
    )
    JarIO.writeJar(tmpIn.getAbsolutePath, entries)

    Goron.run(GoronConfig(
      inputJars = List(tmpIn.getAbsolutePath),
      outputJar = tmpOut.getAbsolutePath,
      entryPoints = List("com/example/Main"),
      eliminateDeadCode = true,
      optInlinerEnabled = false,
      optClosureInvocations = false,
      optLocalOptimizations = false,
    ))

    val result = JarIO.readJar(tmpOut.getAbsolutePath)
    val classNames = result.filter(_.isClass).map(_.name).toSet
    assert(classNames.contains("com/example/Main.class"))
    assert(classNames.contains("com/example/Used.class"))
    assert(!classNames.contains("com/example/Unused.class"))
  }

  test("Global inlining roundtrip") {
    val tmpIn = File.createTempFile("goron-test-inline-in", ".jar")
    val tmpOut = File.createTempFile("goron-test-inline-out", ".jar")
    tmpIn.deleteOnExit()
    tmpOut.deleteOnExit()

    import scala.tools.asm._

    val helperBytes = {
      val cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
      cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "com/example/Helper", null, "java/lang/Object", null)
      val mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "twice", "(I)I", null, null)
      mv.visitCode()
      mv.visitVarInsn(Opcodes.ILOAD, 0)
      mv.visitVarInsn(Opcodes.ILOAD, 0)
      mv.visitInsn(Opcodes.IADD)
      mv.visitInsn(Opcodes.IRETURN)
      mv.visitMaxs(2, 1)
      mv.visitEnd()
      cw.visitEnd()
      cw.toByteArray
    }

    val callerBytes = {
      val cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
      cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "com/example/Caller", null, "java/lang/Object", null)
      val mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "callTwice", "(I)I", null, null)
      mv.visitCode()
      mv.visitVarInsn(Opcodes.ILOAD, 0)
      mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/Helper", "twice", "(I)I", false)
      mv.visitInsn(Opcodes.IRETURN)
      mv.visitMaxs(2, 1)
      mv.visitEnd()
      cw.visitEnd()
      cw.toByteArray
    }

    val entries = Seq(
      JarIO.JarEntry("com/example/Helper.class", helperBytes, isClass = true),
      JarIO.JarEntry("com/example/Caller.class", callerBytes, isClass = true),
    )
    JarIO.writeJar(tmpIn.getAbsolutePath, entries)

    Goron.run(GoronConfig(
      inputJars = List(tmpIn.getAbsolutePath),
      outputJar = tmpOut.getAbsolutePath,
      optInlinerEnabled = true,
      optClosureInvocations = true,
      optLocalOptimizations = true,
    ))

    val result = JarIO.readJar(tmpOut.getAbsolutePath)
    assertEquals(result.size, 2)
    for (entry <- result) {
      val cn = new scala.tools.asm.tree.ClassNode()
      new scala.tools.asm.ClassReader(entry.bytes).accept(cn, 0)
      assert(cn.name == "com/example/Helper" || cn.name == "com/example/Caller")
    }
  }

  test("Closed-world analysis marks effectively final") {
    import scala.tools.asm._

    val baseBytes = {
      val cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
      cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "com/example/Base", null, "java/lang/Object", null)
      val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "greet", "()I", null, null)
      mv.visitCode()
      mv.visitInsn(Opcodes.ICONST_1)
      mv.visitInsn(Opcodes.IRETURN)
      mv.visitMaxs(1, 1)
      mv.visitEnd()
      cw.visitEnd()
      cw.toByteArray
    }

    val leafBytes = {
      val cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES)
      cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC, "com/example/Leaf", null, "com/example/Base", null)
      val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "greet", "()I", null, null)
      mv.visitCode()
      mv.visitInsn(Opcodes.ICONST_2)
      mv.visitInsn(Opcodes.IRETURN)
      mv.visitMaxs(1, 1)
      mv.visitEnd()
      cw.visitEnd()
      cw.toByteArray
    }

    val baseCn = new scala.tools.asm.tree.ClassNode()
    new ClassReader(baseBytes).accept(baseCn, 0)
    val leafCn = new scala.tools.asm.tree.ClassNode()
    new ClassReader(leafBytes).accept(leafCn, 0)

    val hierarchy = ClosedWorldAnalysis.buildHierarchy(Seq(baseCn, leafCn))

    assert(hierarchy.effectivelyFinalClasses.contains("com/example/Leaf"))
    assert(!hierarchy.effectivelyFinalClasses.contains("com/example/Base"))
    assert(hierarchy.effectivelyFinalMethods.contains(("com/example/Leaf", "greet", "()I")))
    assert(!hierarchy.effectivelyFinalMethods.contains(("com/example/Base", "greet", "()I")))
  }
}
