package goron.bench.apps

import goron.GoronConfig
import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit

/** Benchmark for circe JSON library optimization.
  *
  * Circe uses sealed ADT hierarchies and pattern matching extensively.
  * The workload encodes/decodes JSON values, exercising real-world JSON processing.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 10, time = 5)
@Fork(value = 1, jvmArgs = Array("-Xmx4g", "-Xms4g"))
class CirceBench {

  private var stockLoader: URLClassLoader = _
  private var optimizedLoader: URLClassLoader = _

  @Setup(Level.Trial)
  def setup(): Unit = {
    val circeCore = BenchmarkUtils.downloadMavenJar("io.circe", "circe-core_2.13", "0.14.10")
    val circeNumbers = BenchmarkUtils.downloadMavenJar("io.circe", "circe-numbers_2.13", "0.14.10")
    val catsCore = BenchmarkUtils.downloadMavenJar("org.typelevel", "cats-core_2.13", "2.12.0")
    val catsKernel = BenchmarkUtils.downloadMavenJar("org.typelevel", "cats-kernel_2.13", "2.12.0")

    val scalaLib = findScalaLibrary()
    val jars = Array(circeCore, circeNumbers, catsCore, catsKernel, scalaLib)

    stockLoader = BenchmarkUtils.classLoaderFromJars(jars)

    val entryPoints = List(
      "io/circe/Json$",
      "io/circe/Json",
      "io/circe/JsonObject$",
      "io/circe/JsonObject"
    )
    // Disable DCE: we only specify partial entry points; DCE would strip concrete subclasses
    val config = GoronConfig(inputJars = Nil, outputJar = "", eliminateDeadCode = false)
    val optimizedJar = BenchmarkUtils.optimizeJars(jars, entryPoints, config)
    optimizedLoader = BenchmarkUtils.classLoaderFromJars(Array(optimizedJar))
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    if (stockLoader != null) stockLoader.close()
    if (optimizedLoader != null) optimizedLoader.close()
  }

  @Benchmark
  def stock(bh: Blackhole): Unit = {
    bh.consume(runCirceWorkload(stockLoader))
  }

  @Benchmark
  def goron(bh: Blackhole): Unit = {
    bh.consume(runCirceWorkload(optimizedLoader))
  }

  private def runCirceWorkload(cl: ClassLoader): AnyRef = {
    val jsonClass = cl.loadClass("io.circe.Json$")
    val jsonModule = jsonClass.getField("MODULE$").get(null)

    // Build JSON values via reflection: Json.fromInt, Json.fromString, Json.arr, Json.obj
    val fromIntMethod = jsonClass.getMethod("fromInt", classOf[Int])
    val fromStringMethod = jsonClass.getMethod("fromString", classOf[String])
    val nullMethod = jsonClass.getMethod("Null")

    val jsonInstanceClass = cl.loadClass("io.circe.Json")
    val noSpacesMethod = jsonInstanceClass.getMethod("noSpaces")
    val isNumberMethod = jsonInstanceClass.getMethod("isNumber")
    val isStringMethod = jsonInstanceClass.getMethod("isString")
    val isNullMethod = jsonInstanceClass.getMethod("isNull")

    var result: AnyRef = null
    for (_ <- 0 until 500) {
      val jsonInt = fromIntMethod.invoke(jsonModule, 42: java.lang.Integer)
      val jsonStr = fromStringMethod.invoke(jsonModule, "hello")
      val jsonNull = nullMethod.invoke(jsonModule)

      // Exercise the sealed hierarchy dispatch
      isNumberMethod.invoke(jsonInt)
      isStringMethod.invoke(jsonStr)
      isNullMethod.invoke(jsonNull)

      // Serialize to string
      result = noSpacesMethod.invoke(jsonInt)
      noSpacesMethod.invoke(jsonStr)
      noSpacesMethod.invoke(jsonNull)
    }
    result
  }

  private def findScalaLibrary(): File = {
    val cp = System.getProperty("java.class.path", "")
    cp.split(File.pathSeparator)
      .map(new File(_))
      .find(f => f.getName.contains("scala-library") && f.getName.endsWith(".jar"))
      .getOrElse(throw new RuntimeException("scala-library not found on classpath"))
  }
}
