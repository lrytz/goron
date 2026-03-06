/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron

import goron.testkit.ASMConverters._
import goron.testkit.GoronTesting

import scala.tools.asm.Opcodes._

/** Tests for method-level optimizations (dead stores, nullness, box/unbox, jump simplification). Adapted from
  * scala.tools.nsc.backend.jvm.opt.MethodLevelOptsTest.
  *
  * Tests compile source with no optimizations, then run through goron's local optimizer.
  */
class MethodLevelOptsTest extends GoronTesting {
  override def goronConfig = super.goronConfig.copy(
    optInlinerEnabled = false,
    optClosureInvocations = false,
    optLocalOptimizations = true
  )

  test("eliminate empty try") {
    val code = """class C { def f = { try {} catch { case _: Throwable => 0; () }; 1 } }"""
    val c = compileAndOptimizeClass(code)
    val m = getMethod(c, "f")
    assertSameCode(m, List(Op(ICONST_1), Op(IRETURN)))
  }

  test("eliminate load boxed unit") {
    val code = """class C { def f = { try {} catch { case _: Throwable => 0 }; 1 } }"""
    val c = compileAndOptimizeClass(code)
    val m = getMethod(c, "f")
    assert(m.handlers.isEmpty)
    assertSameCode(m, List(Op(ICONST_1), Op(IRETURN)))
  }

  test("simplify jumps in try-catch-finally") {
    val code =
      """class C {
        |  def f: Int =
        |    try { return 1 }
        |    catch { case _: Throwable => return 2 }
        |    finally { return 3; val x = try 10 catch { case _: Throwable => 11 }; println(x) }
        |}
      """.stripMargin
    val c = compileAndOptimizeClass(code)
    val m = getMethod(c, "f")
    assert(m.handlers.isEmpty)
    assertSameCode(m, List(Op(ICONST_3), Op(IRETURN)))
  }

  test("null store-load elimination") {
    val code =
      """class C {
        |  def t = {
        |    val x = null
        |    x.toString
        |  }
        |}
      """.stripMargin
    val c = compileAndOptimizeClass(code)
    assertSameCode(
      getMethod(c, "t"),
      List(Op(ACONST_NULL), InvokeVirtual("java/lang/Object", "toString", "()Ljava/lang/String;"), Op(ARETURN))
    )
  }

  test("dead store reference elimination") {
    val code =
      """class C {
        |  def t = {
        |    var a = "a"
        |    a = "b"
        |    a = "c"
        |    a
        |  }
        |}
      """.stripMargin
    val c = compileAndOptimizeClass(code)
    assertSameCode(getMethod(c, "t"), List(Ldc(LDC, "c"), Op(ARETURN)))
  }

  test("rewrite specialized closure call") {
    // With local opts only, the INVOKEDYNAMIC for unused closures remains but the specialized
    // closure call is rewritten to a direct static call.
    val code =
      """class C {
        |  def t = {
        |    val f1 = (x: Int) => println(x)
        |    val f2 = (x: Int, y: Long) => x == y
        |    f1(1)
        |    f2(3, 4)
        |  }
        |}
      """.stripMargin
    val c = compileAndOptimizeClass(code)
    val t = getMethod(c, "t")
    // After local opts, the specialized apply methods should be called, not generic apply
    val invokes = t.instructions.collect { case i: Invoke => i.name }
    assert(!invokes.contains("apply"), s"Expected no generic apply: ${t.instructions.mkString("\n")}")
  }

  test("nullness optimizations") {
    val code =
      """class C {
        |  def t1 = {
        |    val a = new C
        |    if (a == null)
        |      println()
        |    a
        |  }
        |
        |  def t2 = null.asInstanceOf[Long]
        |
        |  def t6 = {
        |    var a = null
        |    var i = null
        |    a = i
        |    a
        |  }
        |
        |  def t7 = {
        |    val a = null
        |    a.isInstanceOf[String]
        |  }
        |}
      """.stripMargin
    val c = compileAndOptimizeClass(code)
    assertSameSummary(getMethod(c, "t1"), List(NEW, DUP, "<init>", ARETURN))
    assertSameCode(getMethod(c, "t2"), List(Op(LCONST_0), Op(LRETURN)))
    assertSameCode(getMethod(c, "t6"), List(Op(ACONST_NULL), Op(ARETURN)))
    assertSameCode(getMethod(c, "t7"), List(Op(ICONST_0), Op(IRETURN)))
  }

  test("eliminate redundant null check") {
    val code =
      """class C {
        |  def t(x: Object) = {
        |    val bool = x == null
        |    if (x != null) 1 else 0
        |  }
        |}
      """.stripMargin
    val c = compileAndOptimizeClass(code)
    assertSameCode(
      getMethod(c, "t"),
      List(VarOp(ALOAD, 1), Jump(IFNULL, Label(6)), Op(ICONST_1), Op(IRETURN), Label(6), Op(ICONST_0), Op(IRETURN))
    )
  }

  test("branch-sensitive nullness") {
    val code =
      """class C {
        |  def t1(x: Object) = {
        |    if (x != null)
        |      if (x == null) println()
        |    0
        |  }
        |
        |  def t2(x: String) = {
        |    x.trim
        |    if (x == null) println()
        |    0
        |  }
        |}
      """.stripMargin
    val c = compileAndOptimizeClass(code)
    assertSameSummary(getMethod(c, "t1"), List(ICONST_0, IRETURN))
    assertSameSummary(getMethod(c, "t2"), List(ALOAD, "trim", POP, ICONST_0, IRETURN))
  }
}
