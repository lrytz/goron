/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron

import goron.optimizer.opt.InlineInfoAttribute
import goron.testkit.GoronTesting

import scala.jdk.CollectionConverters._
import scala.tools.asm.Opcodes

/** Integration tests that run the full goron pipeline over user classes + scala-library. These test whole-program
  * properties: DCE, inlining with real library classes, and closed-world optimizations.
  */
class IntegrationTest extends GoronTesting {

  override def goronConfig = super.goronConfig.copy(
    optInlinerEnabled = true,
    optClosureInvocations = true,
    optLocalOptimizations = true,
    eliminateDeadCode = true,
    closedWorld = true
  )

  // --- DCE tests ---

  test("unreachable user class is eliminated") {
    val code =
      """object Main {
        |  def main(args: Array[String]): Unit = println("hello")
        |}
        |class Unused {
        |  def foo: Int = 42
        |}
      """.stripMargin
    val survivors = compileAndRunFullPipeline(code, Set("Main"))
    val names = survivingClassNames(survivors)
    assert(names.contains("Main$"), s"Main$$ should survive, got: $names")
    assert(!names.contains("Unused"), s"Unused should be eliminated, got: $names")
    assertEquals(runMain(survivors), "hello")
  }

  test("class reachable through method reference is retained") {
    val code =
      """object Main {
        |  def main(args: Array[String]): Unit = {
        |    val h = new Helper
        |    println(h.compute)
        |  }
        |}
        |class Helper {
        |  def compute: Int = 99
        |}
      """.stripMargin
    val survivors = compileAndRunFullPipeline(code, Set("Main"))
    val names = survivingClassNames(survivors)
    assert(names.contains("Main$"))
    assert(names.contains("Helper"))
    assertEquals(runMain(survivors), "99")
  }

  test("unused subclass eliminated, used subclass retained") {
    val code =
      """abstract class Base { def value: Int }
        |class Used extends Base { def value = 1 }
        |class NotUsed extends Base { def value = 2 }
        |object Main {
        |  def main(args: Array[String]): Unit = {
        |    val b: Base = new Used
        |    println(b.value)
        |  }
        |}
      """.stripMargin
    val survivors = compileAndRunFullPipeline(code, Set("Main"))
    val names = survivingClassNames(survivors)
    assert(names.contains("Used"), s"Used should survive: $names")
    assert(names.contains("Base"), s"Base should survive: $names")
    assert(!names.contains("NotUsed"), s"NotUsed should be eliminated: $names")
    assertEquals(runMain(survivors), "1")
  }

  test("scala-library classes mostly eliminated in simple app") {
    val code =
      """object Main {
        |  def main(args: Array[String]): Unit = println("hello")
        |}
      """.stripMargin
    val survivors = compileAndRunFullPipeline(code, Set("Main"))
    val names = survivingClassNames(survivors)
    val scalaLibCount = names.count(_.startsWith("scala/"))
    val totalLibClasses = goron.testkit.GoronTesting.scalaLibraryNodes.size
    // Method-level DCE: a println app should keep only a small fraction of scala-library
    assert(
      scalaLibCount < 200,
      s"Expected <200 scala-library classes for println app, but kept $scalaLibCount of $totalLibClasses"
    )
    assertEquals(runMain(survivors), "hello")
  }

  // --- Inlining tests ---

  test("cross-class @inline method inlined in full pipeline") {
    val code =
      """class Util {
        |  @inline final def doubled(x: Int): Int = x + x
        |}
        |object Main {
        |  def main(args: Array[String]): Unit = {
        |    val u = new Util
        |    println(u.doubled(21))
        |  }
        |}
      """.stripMargin
    val survivors = compileAndRunFullPipeline(code, Set("Main"))
    val mainClass = findClass(survivors, "Main$")
    val mainMethod = getMethod(mainClass, "main")
    assertDoesNotInvoke(mainMethod, "doubled")
    assertEquals(runMain(survivors), "42")
  }

  test("closure optimization after inlining removes InvokeDynamic") {
    val code =
      """class C {
        |  @inline final def mapIt(x: Int, f: Int => Int): Int = f(x)
        |  def test: Int = mapIt(5, _ + 1)
        |}
        |object Main {
        |  def main(args: Array[String]): Unit = println(new C().test)
        |}
      """.stripMargin
    val survivors = compileAndRunFullPipeline(code, Set("Main"))
    val c = findClass(survivors, "C")
    val testMethod = getMethod(c, "test")
    assertNoIndy(testMethod)
    assertEquals(runMain(survivors), "6")
  }

  // --- Closed-world test ---

  test("closed-world marks leaf class methods as ACC_FINAL") {
    val code =
      """class OnlyImpl {
        |  def compute(x: Int): Int = x * 2
        |}
        |object Main {
        |  def main(args: Array[String]): Unit = {
        |    val o = new OnlyImpl
        |    println(o.compute(21))
        |  }
        |}
      """.stripMargin
    // Disable DCE so the method survives for inspection (with closed-world + inlining,
    // the method may be inlined and then stripped, which is correct but prevents ACC_FINAL checks)
    val survivors =
      compileAndRunFullPipeline(code, Set("Main"), goronConfig.copy(closedWorld = true, eliminateDeadCode = false))
    val onlyImpl = findClass(survivors, "OnlyImpl")
    val computeMethod = getAsmMethod(onlyImpl, "compute")
    // In closed-world, OnlyImpl has no subclasses, so compute should be marked ACC_FINAL
    assert(
      (computeMethod.access & Opcodes.ACC_FINAL) != 0,
      s"Expected compute to be marked ACC_FINAL in closed-world mode"
    )
    assertEquals(runMain(survivors), "42")
  }

  test("closed-world updates InlineInfo for non-leaf class methods") {
    val code =
      """abstract class Base {
        |  def common(x: Int): Int = x + 1
        |  def overridden(x: Int): Int = x
        |}
        |class Sub extends Base {
        |  override def overridden(x: Int): Int = x * 2
        |}
        |object Main {
        |  def main(args: Array[String]): Unit = {
        |    val b: Base = new Sub
        |    println(b.common(10) + b.overridden(5))
        |  }
        |}
      """.stripMargin
    // Disable DCE so methods survive for attribute inspection
    val survivors =
      compileAndRunFullPipeline(code, Set("Main"), goronConfig.copy(closedWorld = true, eliminateDeadCode = false))
    val base = findClass(survivors, "Base")

    // Read InlineInfo from the ScalaInlineInfo attribute
    val inlineInfo = base.attrs.asScala.collectFirst { case a: InlineInfoAttribute => a.inlineInfo }
    assert(inlineInfo.isDefined, "Base should have ScalaInlineInfo attribute")
    val methodInfos = inlineInfo.get.methodInfos

    // `common` has no override in Sub → should be effectively final via closed-world
    val commonInfo = methodInfos.get(("common", "(I)I"))
    assert(commonInfo.isDefined, s"common should be in InlineInfo: ${methodInfos.keys}")
    assert(commonInfo.get.effectivelyFinal, "common should be effectively final (no overrides)")

    // `overridden` IS overridden in Sub → should NOT be effectively final
    val overriddenInfo = methodInfos.get(("overridden", "(I)I"))
    assert(overriddenInfo.isDefined, s"overridden should be in InlineInfo: ${methodInfos.keys}")
    assert(!overriddenInfo.get.effectivelyFinal, "overridden should NOT be effectively final (has override)")

    assertEquals(runMain(survivors), "21")
  }

  // --- Method-level DCE tests ---

  test("unreachable method is stripped from retained class") {
    val code =
      """class Helper {
        |  def used: Int = 42
        |  def unused: Int = 99
        |}
        |object Main {
        |  def main(args: Array[String]): Unit = {
        |    val h = new Helper
        |    println(h.used)
        |  }
        |}
      """.stripMargin
    // Disable closed-world: this test checks DCE behavior, not closed-world inlining
    val survivors = compileAndRunFullPipeline(code, Set("Main"), goronConfig.copy(closedWorld = false))
    val helper = findClass(survivors, "Helper")
    val methodNames = helper.methods.asScala.map(_.name).toSet
    assert(methodNames.contains("used"), s"used should survive: $methodNames")
    assert(!methodNames.contains("unused"), s"unused should be stripped: $methodNames")
    assert(methodNames.contains("<init>"), s"<init> should survive: $methodNames")
    assertEquals(runMain(survivors), "42")
  }

  test("interface method implementation retained when called through interface") {
    val code =
      """trait Greeter { def greet: String }
        |class Hello extends Greeter {
        |  def greet: String = "hello"
        |  def unused: String = "nope"
        |}
        |object Main {
        |  def main(args: Array[String]): Unit = {
        |    val g: Greeter = new Hello
        |    println(g.greet)
        |  }
        |}
      """.stripMargin
    // Disable closed-world: this test checks DCE behavior, not closed-world inlining
    val survivors = compileAndRunFullPipeline(code, Set("Main"), goronConfig.copy(closedWorld = false))
    val hello = findClass(survivors, "Hello")
    val methodNames = hello.methods.asScala.map(_.name).toSet
    assert(methodNames.contains("greet"), s"greet should survive: $methodNames")
    assert(!methodNames.contains("unused"), s"unused should be stripped: $methodNames")
    assertEquals(runMain(survivors), "hello")
  }

  test("class only referenced from stripped method is eliminated") {
    val code =
      """class OnlyUsedByUnused { def value: Int = 123 }
        |class Helper {
        |  def used: Int = 42
        |  def unused: Int = new OnlyUsedByUnused().value
        |}
        |object Main {
        |  def main(args: Array[String]): Unit = {
        |    val h = new Helper
        |    println(h.used)
        |  }
        |}
      """.stripMargin
    val survivors = compileAndRunFullPipeline(code, Set("Main"))
    val names = survivingClassNames(survivors)
    assert(names.contains("Helper"), s"Helper should survive: $names")
    assert(
      !names.contains("OnlyUsedByUnused"),
      s"OnlyUsedByUnused should be eliminated (only referenced from stripped method): $names"
    )
    assertEquals(runMain(survivors), "42")
  }

  test("runMain classloader blocks eliminated scala-library classes") {
    val code =
      """object Main {
        |  def main(args: Array[String]): Unit = println("hello")
        |}
      """.stripMargin
    val survivors = compileAndRunFullPipeline(code, Set("Main"))
    // Removing a needed scala-library class should cause a ClassNotFoundException,
    // not silently fall back to the unoptimized version on the test classpath.
    val broken = survivors.filterNot(_.name == "scala/Console$")
    val e = intercept[java.lang.reflect.InvocationTargetException] {
      runMain(broken)
    }
    // The cause chain is typically NoClassDefFoundError -> ClassNotFoundException
    def hasCause(t: Throwable, cls: Class[_]): Boolean =
      t != null && (cls.isInstance(t) || hasCause(t.getCause, cls))
    assert(
      hasCause(e, classOf[ClassNotFoundException]),
      s"Expected ClassNotFoundException in cause chain, got: ${e.getCause}"
    )
  }

  test("for-comprehension over Range prints correctly") {
    val code =
      """object T {
        |  def main(): Unit = {
        |    for (i <- 1 to 10)
        |      println(i)
        |  }
        |}
      """.stripMargin
    val survivors = compileAndRunFullPipeline(code, Set("T"))
    assertEquals(runMain(survivors, "T"), (1 to 10).mkString("\n"))
  }
}
