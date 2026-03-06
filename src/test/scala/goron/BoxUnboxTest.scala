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

/** Tests for box/unbox elimination. Adapted from scala.tools.nsc.backend.jvm.opt.BoxUnboxTest.
  */
class BoxUnboxTest extends GoronTesting {
  override def goronConfig = super.goronConfig.copy(
    optInlinerEnabled = false,
    optClosureInvocations = false,
    optLocalOptimizations = true
  )

  test("eliminate unused box-unbox") {
    val code =
      """class C {
        |  def t(a: Long): Int = {
        |    val t = 3 + a
        |    val u = a + t
        |    val v: Any = u
        |    val w = (v, a)
        |    val x = v.asInstanceOf[Long]
        |    val z = (java.lang.Long.valueOf(a), t)
        |    0
        |  }
        |}
      """.stripMargin
    val c = compileAndOptimizeClass(code)
    assertSameCode(getMethod(c, "t"), List(Op(ICONST_0), Op(IRETURN)))
  }

  test("box-unbox primitive") {
    val code =
      """class C {
        |  def t3(i: Integer): Int = i.asInstanceOf[Int]
        |
        |  def t4(l: Long): Any = l
        |
        |  def t5(i: Int): Int = {
        |    val b = Integer.valueOf(i)
        |    b.asInstanceOf[Int] + b.intValue
        |  }
        |
        |  def t8 = null.asInstanceOf[Int]
        |}
      """.stripMargin
    val c = compileAndOptimizeClass(code)
    assertInvoke(getMethod(c, "t3"), "scala/runtime/BoxesRunTime", "unboxToInt")
    assertInvoke(getMethod(c, "t4"), "scala/runtime/BoxesRunTime", "boxToLong")
    assertNoInvoke(getMethod(c, "t5"))
    assertSameSummary(getMethod(c, "t8"), List(ICONST_0, IRETURN))
  }

  test("ref elimination") {
    val code =
      """class C {
        |  import runtime._
        |
        |  def t1 = {
        |    val r = new IntRef(0)
        |    r.elem
        |  }
        |
        |  def t3 = {
        |    val r = LongRef.create(10L)
        |    r.elem += 3
        |    r.elem
        |  }
        |
        |  def t4(b: Boolean) = {
        |    val x = BooleanRef.create(false)
        |    if (b) x.elem = true
        |    if (x.elem) "a" else "b"
        |  }
        |}
      """.stripMargin
    val c = compileAndOptimizeClass(code)
    assertSameSummary(getMethod(c, "t1"), List(ICONST_0, IRETURN))
    assertSameSummary(getMethod(c, "t3"), List(LDC, LDC, LADD, LRETURN))
    assertNoInvoke(getMethod(c, "t4"))
  }

  test("eliminate unused box") {
    // Box is created but never used - should be eliminated by dead store elimination
    val code =
      """class C {
        |  def t(a: Long): Int = {
        |    val v: Any = a       // boxes to Long
        |    val w = (v, a)       // Tuple2
        |    val x = java.lang.Long.valueOf(a)
        |    0
        |  }
        |}
      """.stripMargin
    val c = compileAndOptimizeClass(code)
    assertSameCode(getMethod(c, "t"), List(Op(ICONST_0), Op(IRETURN)))
  }
}
