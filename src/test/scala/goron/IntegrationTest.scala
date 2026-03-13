/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron

import goron.optimizer.opt.InlineInfoAttribute
import goron.testkit.ASMConverters._
import goron.testkit.GoronTesting

import scala.jdk.CollectionConverters._
import scala.tools.asm.Opcodes
import scala.tools.asm.tree.ClassNode

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

  // --- Collection pipeline tests ---

  test("mapFilterSum produces correct result") {
    val code =
      """object Main {
        |  def main(args: Array[String]): Unit = {
        |    println((1 to 1000).map(_ * 2).filter(_ > 50).sum)
        |  }
        |}
      """.stripMargin
    val survivors = compileAndRunFullPipeline(code, Set("Main"))
    assertEquals(runMain(survivors), "1000350")

    val mainClass = findClass(survivors, "Main$")
    val mainMethod = getMethod(mainClass, "main")
    println(decompileClass(survivors, mainClass))

    val invokes = mainMethod.instructions.collect { case i: Invoke => s"${i.owner}.${i.name}" }
    println(s"=== Remaining invocations: ${invokes.mkString(", ")} ===")
    val indys = mainMethod.instructions.collect { case i: InvokeDynamic => i.name }
    println(s"=== InvokeDynamic (lambdas): ${indys.mkString(", ")} ===")

    // Current state: map loop is inlined, closure body inlined, map lambda eliminated.
    // Remaining issues:
    // - filter lambda still allocated (interface dispatch blocks filter inlining)
    // - filter/sum not inlined (interface dispatch)
    // - intermediate collections and boxing remain
    // See individual tests below for each issue.
  }

  // --- Collection pipeline optimization issues (individual) ---

  test("map closure eliminated after inlining") {
    // After the closure optimizer rewrites closure.apply() → direct body call, the
    // closure object becomes unused. The chain of local optimizations eliminates it:
    // 1. Nullness analysis proves the LambdaMetaFactory result is non-null
    // 2. The null-check on the closure (IFNONNULL) is replaced by POP + GOTO
    // 3. eliminatePushPop removes the ALOAD + POP pair
    // 4. eliminateStaleStores replaces the now-consumerless ASTORE with POP
    // 5. eliminatePushPop removes the INVOKEDYNAMIC via handleClosureInst
    val code =
      """object Main {
        |  def main(args: Array[String]): Unit = {
        |    val xs = (1 to 10).map(_ * 2)
        |    println(xs.size)
        |  }
        |}
      """.stripMargin
    val survivors = compileAndRunFullPipeline(code, Set("Main"))
    assertEquals(runMain(survivors), "10")

    val mainClass = findClass(survivors, "Main$")
    val mainMethod = getMethod(mainClass, "main")

    // The map closure should be completely eliminated — no INVOKEDYNAMIC remains
    assertNoIndy(mainMethod)
    // The closure body (* 2) should be inlined directly in the loop
    val invokes = mainMethod.instructions.collect { case i: Invoke => s"${i.owner}.${i.name}" }
    assert(!invokes.exists(_.contains("anonfun")), s"Closure body method should be inlined, got: $invokes")
  }

  test("issue: filter not inlined — interface dispatch blocks devirtualization") {
    // filter is called on the result of map, which has static type IndexedSeq (interface).
    // The inliner requires isStaticallyResolved for safeToInline, which fails for
    // interface calls. Even closed-world analysis can't help because IndexedSeq has
    // multiple implementations. Would need type flow analysis to know that
    // IndexedSeq$.newBuilder().result() returns a specific concrete type (Vector).
    val code =
      """object Main {
        |  def main(args: Array[String]): Unit = {
        |    val xs = (1 to 10).map(_ * 2).filter(_ > 5)
        |    println(xs.size)
        |  }
        |}
      """.stripMargin
    val survivors = compileAndRunFullPipeline(code, Set("Main"))
    assertEquals(runMain(survivors), "8")

    val mainClass = findClass(survivors, "Main$")
    val mainMethod = getMethod(mainClass, "main")
    println(decompileClass(survivors, mainClass))

    // filter is still called via interface dispatch
    val invokes = mainMethod.instructions.collect { case i: Invoke => s"${i.owner}.${i.name}" }
    println(s"=== Remaining invocations: ${invokes.mkString(", ")} ===")
    assertInvoke(mainMethod, "scala/collection/immutable/IndexedSeq", "filter")
    // TODO: after fixing, filter's loop should be inlined and assertDoesNotInvoke(mainMethod, "filter")
  }

  test("issue: boxing in map loop — unboxToInt/Integer.valueOf") {
    // The inlined map loop iterates with Iterator[Object], unboxes to int, applies
    // the function, then re-boxes with Integer.valueOf before addOne. The specialized
    // JFunction1$mcII$sp avoids boxing for the closure call itself, but the
    // iterator/builder pipeline still boxes because it uses generic Object types.
    val code =
      """object Main {
        |  def main(args: Array[String]): Unit = {
        |    val xs = (1 to 10).map(_ + 1)
        |    println(xs.sum)
        |  }
        |}
      """.stripMargin
    val survivors = compileAndRunFullPipeline(code, Set("Main"))
    assertEquals(runMain(survivors), "65")

    val mainClass = findClass(survivors, "Main$")
    val mainMethod = getMethod(mainClass, "main")

    val invokes = mainMethod.instructions.collect { case i: Invoke => s"${i.owner}.${i.name}" }
    val hasBoxing = invokes.exists(_.contains("BoxesRunTime")) || invokes.exists(_.contains("Integer.valueOf"))
    println(s"=== Boxing calls: ${invokes.filter(i => i.contains("Box") || i.contains("valueOf")).mkString(", ")} ===")
    // TODO: after fixing, assert: assert(!hasBoxing)
  }

  private def textifyMethod(classNode: ClassNode, methodName: String): String = {
    val m = getAsmMethod(classNode, methodName)
    val textifier = new scala.tools.asm.util.Textifier()
    m.accept(new scala.tools.asm.util.TraceMethodVisitor(textifier))
    val sw = new java.io.StringWriter()
    textifier.print(new java.io.PrintWriter(sw))
    s"=== Bytecode: ${classNode.name}.$methodName ===\n${sw.toString}"
  }

  private def decompileClass(survivors: List[ClassNode], classNode: ClassNode): String = {
    import org.benf.cfr.reader.api.{CfrDriver, ClassFileSource, OutputSinkFactory}
    import java.util

    val pp = GoronTesting.createPostProcessor(goronConfig)
    for (cn <- survivors) pp.byteCodeRepository.add(cn, Some("goron-test"))
    pp.setInnerClasses(classNode)
    val classBytes = pp.serializeClass(classNode)
    val className = classNode.name.replace('/', '.')
    val classFile = classNode.name + ".class"

    val result = new StringBuilder
    val sink = new OutputSinkFactory {
      override def getSupportedSinks(sinkType: OutputSinkFactory.SinkType, collection: util.Collection[OutputSinkFactory.SinkClass]) =
        util.Arrays.asList(OutputSinkFactory.SinkClass.STRING)
      override def getSink[T](sinkType: OutputSinkFactory.SinkType, sinkClass: OutputSinkFactory.SinkClass) =
        new OutputSinkFactory.Sink[T] {
          override def write(s: T): Unit =
            if (sinkType == OutputSinkFactory.SinkType.JAVA) result.append(s.toString)
        }
    }

    val source = new ClassFileSource {
      override def getPossiblyRenamedPath(path: String) = path
      override def addJar(jarPath: String): util.Collection[String] = util.Collections.emptyList()
      override def informAnalysisRelativePathDetail(usePath: String, classFilePath: String): Unit = ()
      override def getClassFileContent(path: String) =
        if (path == classFile) new org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair(classBytes, className)
        else null
    }

    val options = new util.HashMap[String, String]()
    options.put("showversion", "false")
    options.put("hideutf", "false")
    val driver = new CfrDriver.Builder().withClassFileSource(source).withOutputSink(sink).withOptions(options).build()
    driver.analyse(util.Arrays.asList(classFile))
    s"=== CFR decompilation: ${classNode.name} ===\n${result.toString}"
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
