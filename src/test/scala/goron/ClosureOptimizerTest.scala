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

/** Tests for closure invocation optimization.
  * Adapted from scala.tools.nsc.backend.jvm.opt.ClosureOptimizerTest.
  */
class ClosureOptimizerTest extends GoronTesting {
  override def goronConfig = super.goronConfig.copy(
    optInlinerEnabled = true,
    optClosureInvocations = true,
    optLocalOptimizations = true,
  )

  test("nothing-typed closure body") {
    val code =
      """abstract class C {
        |  def isEmpty: Boolean
        |  @inline final def getOrElse[T >: C](f: => T) = if (isEmpty) f else this
        |  def t = getOrElse(throw new Error(""))
        |}
      """.stripMargin
    val c = compileAndOptimizeClass(code)
    assertSameSummary(getMethod(c, "t"), List(ALOAD, "isEmpty", IFEQ /*12*/, NEW, DUP, LDC, "<init>", ATHROW, -1 /*12*/, ALOAD, ARETURN))
  }

  test("null-typed closure body") {
    val code =
      """abstract class C {
        |  def isEmpty: Boolean
        |  @inline final def getOrElse[T >: C](f: => T) = if (isEmpty) f else this
        |  def t = getOrElse(null)
        |}
      """.stripMargin
    val c = compileAndOptimizeClass(code)
    assertSameSummary(getMethod(c, "t"), List(ALOAD, "isEmpty", IFEQ /*9*/, ACONST_NULL, GOTO /*12*/, -1 /*9*/, ALOAD, -1 /*12*/, CHECKCAST, ARETURN))
  }

  test("make LMF cast explicit") {
    val code =
      """class C {
        |  def t(l: List[String]) = {
        |    val fun: String => String = s => s
        |    fun(l.head)
        |  }
        |}
      """.stripMargin
    val c = compileAndOptimizeClass(code)
    assertSameCode(getMethod(c, "t"),
      List(VarOp(ALOAD, 1), InvokeVirtual("scala/collection/immutable/List", "head", "()Ljava/lang/Object;"),
        TypeOp(CHECKCAST, "java/lang/String"), Op(ARETURN)))
  }

  test("closure opt with unreachable code") {
    val code =
      """class C {
        |  @inline final def m = throw new Error("")
        |  def t = {
        |    val f = (x: Int) => x + 1
        |    m
        |    f(10)
        |  }
        |}
      """.stripMargin
    val c = compileAndOptimizeClass(code)
    assertSameSummary(getMethod(c, "t"), List(NEW, DUP, LDC, "<init>", ATHROW))
  }
}
