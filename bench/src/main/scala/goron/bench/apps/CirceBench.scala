package goron.bench.apps

import goron.bench.BenchmarkUtils
import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.io.File
import java.net.URLClassLoader
import java.util.concurrent.TimeUnit

/** Benchmark for circe JSON library optimization.
  *
  * Circe uses sealed ADT hierarchies and pattern matching extensively.
  * The workload builds nested JSON structures and serializes them, exercising
  * real-world JSON processing patterns.
  */
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 10, time = 5)
@Fork(value = 1, jvmArgs = Array("-Xmx4g", "-Xms4g"))
class CirceBench {

  private var stockLoader: URLClassLoader = _
  private var optimizedLoader: URLClassLoader = _

  // Cached reflection handles
  private var stockJson: JsonHandles = _
  private var goronJson: JsonHandles = _

  private class JsonHandles(
      val jsonModule: AnyRef,
      val fromIntMethod: java.lang.reflect.Method,
      val fromStringMethod: java.lang.reflect.Method,
      val nullMethod: java.lang.reflect.Method,
      val noSpacesMethod: java.lang.reflect.Method,
      val isNumberMethod: java.lang.reflect.Method,
      val isStringMethod: java.lang.reflect.Method,
      val isNullMethod: java.lang.reflect.Method
  )

  @Setup(Level.Trial)
  def setup(): Unit = {
    val jars = BenchmarkUtils.resolve("io.circe:circe-core_2.13:0.14.10")

    stockLoader = BenchmarkUtils.classLoaderFromJars(jars)

    val entryPoints = List(
      "io/circe/Json$",
      "io/circe/Json",
      "io/circe/Json$JNull",
      "io/circe/Json$JBoolean",
      "io/circe/Json$JNumber",
      "io/circe/Json$JString",
      "io/circe/Json$JArray",
      "io/circe/Json$JObject",
      "io/circe/JsonObject$",
      "io/circe/JsonObject",
      "io/circe/JsonObject$LinkedHashMapJsonObject"
    )
    val optimizedJar = BenchmarkUtils.optimizeJars(jars, entryPoints)
    optimizedLoader = BenchmarkUtils.classLoaderFromJars(Array(optimizedJar))

    stockJson = setupHandles(stockLoader)
    goronJson = setupHandles(optimizedLoader)
  }

  private def setupHandles(cl: ClassLoader): JsonHandles = {
    val jsonClass = cl.loadClass("io.circe.Json$")
    val jsonModule = jsonClass.getField("MODULE$").get(null)
    val jsonInstanceClass = cl.loadClass("io.circe.Json")

    val fromIntMethod = jsonClass.getMethod("fromInt", classOf[Int])
    val fromStringMethod = jsonClass.getMethod("fromString", classOf[String])
    val nullMethod = jsonClass.getMethod("Null")
    val noSpacesMethod = jsonInstanceClass.getMethod("noSpaces")
    val isNumberMethod = jsonInstanceClass.getMethod("isNumber")
    val isStringMethod = jsonInstanceClass.getMethod("isString")
    val isNullMethod = jsonInstanceClass.getMethod("isNull")

    new JsonHandles(jsonModule, fromIntMethod, fromStringMethod,
      nullMethod, noSpacesMethod, isNumberMethod, isStringMethod, isNullMethod)
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    if (stockLoader != null) stockLoader.close()
    if (optimizedLoader != null) optimizedLoader.close()
  }

  @Benchmark
  def stock(bh: Blackhole): Unit = {
    bh.consume(runWorkload(stockJson))
  }

  @Benchmark
  def goron(bh: Blackhole): Unit = {
    bh.consume(runWorkload(goronJson))
  }

  private def runWorkload(h: JsonHandles): AnyRef = {
    var result: AnyRef = null

    for (_ <- 0 until 5000) {
      // Build various Json values
      val jsonInt = h.fromIntMethod.invoke(h.jsonModule, 42: java.lang.Integer)
      val jsonStr = h.fromStringMethod.invoke(h.jsonModule, "hello world")
      val jsonNull = h.nullMethod.invoke(h.jsonModule)

      // Exercise type-check dispatch across the sealed hierarchy
      h.isNumberMethod.invoke(jsonInt)
      h.isStringMethod.invoke(jsonStr)
      h.isNullMethod.invoke(jsonNull)
      h.isNumberMethod.invoke(jsonStr)
      h.isStringMethod.invoke(jsonInt)
      h.isNullMethod.invoke(jsonInt)

      // Serialize each to string (exercises pattern matching + printing)
      h.noSpacesMethod.invoke(jsonInt)
      h.noSpacesMethod.invoke(jsonStr)
      result = h.noSpacesMethod.invoke(jsonNull)
    }
    result
  }
}
