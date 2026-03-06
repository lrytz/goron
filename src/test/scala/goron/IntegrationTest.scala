package goron

import scala.jdk.CollectionConverters._
import scala.tools.asm.Opcodes

import goron.testkit.ASMConverters._
import goron.testkit.GoronTesting

/** Integration tests that run the full goron pipeline over user classes + scala-library.
  * These test whole-program properties: DCE, inlining with real library classes, and
  * closed-world optimizations.
  */
class IntegrationTest extends GoronTesting {

  override def goronConfig = super.goronConfig.copy(
    optInlinerEnabled = true,
    optClosureInvocations = true,
    optLocalOptimizations = true,
    eliminateDeadCode = true,
    closedWorld = true,
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
    val survivors = compileAndRunFullPipeline(code, Set("Main$"))
    val names = survivingClassNames(survivors)
    assert(names.contains("Main$"), s"Main$$ should survive, got: $names")
    assert(!names.contains("Unused"), s"Unused should be eliminated, got: $names")
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
    val survivors = compileAndRunFullPipeline(code, Set("Main$"))
    val names = survivingClassNames(survivors)
    assert(names.contains("Main$"))
    assert(names.contains("Helper"))
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
    val survivors = compileAndRunFullPipeline(code, Set("Main$"))
    val names = survivingClassNames(survivors)
    assert(names.contains("Used"), s"Used should survive: $names")
    assert(names.contains("Base"), s"Base should survive: $names")
    assert(!names.contains("NotUsed"), s"NotUsed should be eliminated: $names")
  }

  test("scala-library classes mostly eliminated in simple app") {
    val code =
      """object Main {
        |  def main(args: Array[String]): Unit = println("hello")
        |}
      """.stripMargin
    val survivors = compileAndRunFullPipeline(code, Set("Main$"))
    val names = survivingClassNames(survivors)
    val scalaLibCount = names.count(_.startsWith("scala/"))
    val totalLibClasses = goron.testkit.GoronTesting.scalaLibraryNodes.size
    // Method-level DCE: a println app should keep only a small fraction of scala-library
    assert(scalaLibCount < 200,
      s"Expected <200 scala-library classes for println app, but kept $scalaLibCount of $totalLibClasses")
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
    val survivors = compileAndRunFullPipeline(code, Set("Main$"))
    val mainClass = findClass(survivors, "Main$")
    val mainMethod = getMethod(mainClass, "main")
    assertDoesNotInvoke(mainMethod, "doubled")
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
    val survivors = compileAndRunFullPipeline(code, Set("Main$"))
    val c = findClass(survivors, "C")
    val testMethod = getMethod(c, "test")
    assertNoIndy(testMethod)
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
    val survivors = compileAndRunFullPipeline(code, Set("Main$"), goronConfig.copy(closedWorld = true))
    val onlyImpl = findClass(survivors, "OnlyImpl")
    val computeMethod = getAsmMethod(onlyImpl, "compute")
    // In closed-world, OnlyImpl has no subclasses, so compute should be marked ACC_FINAL
    assert((computeMethod.access & Opcodes.ACC_FINAL) != 0,
      s"Expected compute to be marked ACC_FINAL in closed-world mode")
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
    val survivors = compileAndRunFullPipeline(code, Set("Main$"))
    val helper = findClass(survivors, "Helper")
    val methodNames = helper.methods.asScala.map(_.name).toSet
    assert(methodNames.contains("used"), s"used should survive: $methodNames")
    assert(!methodNames.contains("unused"), s"unused should be stripped: $methodNames")
    assert(methodNames.contains("<init>"), s"<init> should survive: $methodNames")
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
    val survivors = compileAndRunFullPipeline(code, Set("Main$"))
    val hello = findClass(survivors, "Hello")
    val methodNames = hello.methods.asScala.map(_.name).toSet
    assert(methodNames.contains("greet"), s"greet should survive: $methodNames")
    assert(!methodNames.contains("unused"), s"unused should be stripped: $methodNames")
  }
}
