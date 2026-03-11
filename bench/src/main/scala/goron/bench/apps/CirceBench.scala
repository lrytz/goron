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
  }

  @TearDown(Level.Trial)
  def teardown(): Unit = {
    if (stockLoader != null) stockLoader.close()
    if (optimizedLoader != null) optimizedLoader.close()
  }

  @Benchmark
  def stock(bh: Blackhole): Unit = {
    bh.consume(runWorkload(stockLoader))
  }

  @Benchmark
  def goron(bh: Blackhole): Unit = {
    bh.consume(runWorkload(optimizedLoader))
  }

  private def runWorkload(cl: ClassLoader): AnyRef = {
    val jsonClass = cl.loadClass("io.circe.Json$")
    val jsonModule = jsonClass.getField("MODULE$").get(null)

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
}
