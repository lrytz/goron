/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron

import scala.tools.asm.Opcodes._

import goron.testkit.ASMConverters._
import goron.testkit.GoronTesting

/** Tests for cross-class inlining through goron's optimizer.
  * Adapted from scala.tools.nsc.backend.jvm.opt.InlinerTest.
  *
  * These tests compile with no compiler-level optimizations, then run the full
  * goron pipeline (inlining + closure optimization + local optimizations).
  */
class InlinerTest extends GoronTesting {
  override def goronConfig = super.goronConfig.copy(
    optInlinerEnabled = true,
    optClosureInvocations = true,
    optLocalOptimizations = true,
  )

  test("inline simple @inline final") {
    val code =
      """class C {
        |  @inline final def f = 0
        |  final def g = 1
        |
        |  def test = f + (g: @noinline)
        |}
      """.stripMargin
    val c = compileAndOptimizeClass(code)
    val instructions = getInstructions(c, "test")
    assert(instructions.contains(Op(ICONST_0)), s"Expected ICONST_0 (inlined f):\n${instructions.mkString("\n")}")
  }

  test("arraycopy inlining") {
    val code =
      """object Platform {
        |  @inline def arraycopy(src: AnyRef, srcPos: Int, dest: AnyRef, destPos: Int, length: Int): Unit = {
        |    System.arraycopy(src, srcPos, dest, destPos, length)
        |  }
        |}
        |class C {
        |  def f(src: AnyRef, srcPos: Int, dest: AnyRef, destPos: Int, length: Int): Unit = {
        |    Platform.arraycopy(src, srcPos, dest, destPos, length)
        |  }
        |}
      """.stripMargin
    val classes = compileAndOptimize(code)
    val c = findClass(classes, "C")
    val ins = getInstructions(c, "f")
    val invokeSysArraycopy = Invoke(INVOKESTATIC, "java/lang/System", "arraycopy", "(Ljava/lang/Object;ILjava/lang/Object;II)V", itf = false)
    assert(ins contains invokeSysArraycopy, s"Expected System.arraycopy:\n${ins.mkString("\n")}")
  }

  test("@inline in trait") {
    val code =
      """trait T {
        |  @inline final def f = 0
        |}
        |class C {
        |  def g(t: T) = t.f
        |}
      """.stripMargin
    val classes = compileAndOptimize(code)
    val c = findClass(classes, "C")
    assertNoInvoke(getMethod(c, "g"))
  }

  test("inline higher-order method") {
    val code =
      """class C {
        |  @inline final def h(f: Int => Int): Int = f(0)
        |  def t1 = h(x => x + 1)
        |}
      """.stripMargin
    val c = compileAndOptimizeClass(code)
    // After inlining h and closure optimization, the lambda body should be called directly
    val ins = getMethod(c, "t1").instructions
    assertDoesNotInvoke(ins, "h")
  }

  test("inline into trait method body") {
    val code =
      """trait T {
        |  @inline final def f = 1
        |  def g = f + f
        |}
        |class C extends T
      """.stripMargin
    val classes = compileAndOptimize(code)
    val t = findClass(classes, "T")
    val gIns = getInstructions(t, "g")
    // f should be inlined into g: no invoke of f
    assertDoesNotInvoke(gIns, "f")
  }

  test("inline final method from final class") {
    val code =
      """final class C {
        |  def f: Int = 1
        |  def g: Int = f + 2
        |}
      """.stripMargin
    val c = compileAndOptimizeClass(code)
    val gIns = getInstructions(c, "g")
    // In a final class, f is effectively final and can be inlined
    assertDoesNotInvoke(gIns, "f")
  }

  test("inline with try-catch callee") {
    val code =
      """class C {
        |  @inline final def f: Int = try { 1 } catch { case _: Exception => 2 }
        |  def g = f + 1
        |}
      """.stripMargin
    // The method with handler should be inlinable into g since there's nothing on the stack
    val c = compileAndOptimizeClass(code)
    val gIns = getInstructions(c, "g")
    assertDoesNotInvoke(gIns, "f")
  }
}
