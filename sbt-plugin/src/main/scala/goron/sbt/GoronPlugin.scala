package goron.sbt

import sbt._
import sbt.Keys._
import sbtassembly.AssemblyPlugin
import sbtassembly.AssemblyPlugin.autoImport._

/** sbt plugin that optimizes the sbt-assembly output with goron.
  *
  * Extends sbt-assembly: the `goronAssembly` task runs `assembly` first, then
  * applies goron link-time optimization (inlining, closure elimination, DCE)
  * to the assembled fat jar.
  *
  * Goron is resolved automatically from the local Ivy/Maven repository.
  * Publish goron locally first: `cd goron && sbt publishLocal`
  *
  * Usage in build.sbt:
  * {{{
  * enablePlugins(GoronPlugin)
  *
  * goronEntryPoints := Seq("com/example/Main")
  * }}}
  *
  * Then run: `sbt goronAssembly`
  */
object GoronPlugin extends AutoPlugin {

  override def requires = AssemblyPlugin
  override def trigger = noTrigger // must be explicitly enabled

  val GoronConfig = config("goron").hide

  object autoImport {
    val goronAssembly = taskKey[File]("Run sbt-assembly then optimize with goron")
    val goronEntryPoints = settingKey[Seq[String]]("Entry point classes for reachability analysis (internal names, e.g. com/example/Main)")
    val goronVersion = settingKey[String]("Goron optimizer version to resolve")
    val goronJavaOptions = settingKey[Seq[String]]("JVM options for the forked goron process")
    val goronVerbose = settingKey[Boolean]("Enable verbose goron output")
    val goronInlinerEnabled = settingKey[Boolean]("Enable the inliner")
    val goronClosureOptimization = settingKey[Boolean]("Enable closure invocation optimization")
    val goronDeadCodeElimination = settingKey[Boolean]("Enable dead code elimination")
    val goronClosedWorld = settingKey[Boolean]("Enable closed-world analysis for devirtualization")
    val goronLogInline = settingKey[Option[String]]("Log inline decisions (None = off, Some(filter) = on)")
  }

  import autoImport._

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    ivyConfigurations += GoronConfig,
    goronVersion := "0.1.0-SNAPSHOT",
    libraryDependencies += "org.scala-lang" % "goron_2.13" % goronVersion.value % GoronConfig,
    goronEntryPoints := Seq.empty,
    goronJavaOptions := Seq("-Xmx2g", "-Xms512m"),
    goronVerbose := false,
    goronInlinerEnabled := true,
    goronClosureOptimization := true,
    goronDeadCodeElimination := true,
    goronClosedWorld := true,
    goronLogInline := None,
    goronAssembly := {
      val log = streams.value.log
      val inputJar = assembly.value // run assembly first
      val outputJar = target.value / s"${name.value}-goron.jar"
      val entries = {
        val explicit = goronEntryPoints.value
        if (explicit.nonEmpty) explicit
        else (assembly / mainClass).value.map(_.replace('.', '/')).toSeq
      }

      // Resolve goron and its dependencies from the GoronConfig configuration
      val goronClasspath = update.value
        .select(configurationFilter(GoronConfig.name))
        .map(_.getAbsolutePath)
        .mkString(java.io.File.pathSeparator)

      if (goronClasspath.isEmpty)
        sys.error("Could not resolve goron. Run `sbt publishLocal` in the goron project first.")

      val javaOpts = goronJavaOptions.value
      val verbose = goronVerbose.value
      val inliner = goronInlinerEnabled.value
      val closures = goronClosureOptimization.value
      val dce = goronDeadCodeElimination.value
      val closed = goronClosedWorld.value
      val logInline = goronLogInline.value

      if (entries.isEmpty)
        log.warn("goronEntryPoints is empty — goron will not perform DCE or reachability analysis")

      log.info(s"Optimizing ${inputJar.getName} with goron...")

      val args = new scala.collection.mutable.ListBuffer[String]()
      args ++= Seq("--input", inputJar.getAbsolutePath)
      args ++= Seq("--output", outputJar.getAbsolutePath)
      for (ep <- entries) { args += "--entry"; args += ep }
      if (verbose) args += "--verbose"
      if (!inliner || !closures) args += "--no-inline"
      if (!dce) args += "--no-dce"
      logInline.foreach { filter => args += "--log-inline"; args += filter }

      val javaHome = sys.props.get("java.home").map(file)
      val javaBin = javaHome.map(_ / "bin" / "java").getOrElse(file("java"))

      val cmd = Seq(javaBin.getAbsolutePath) ++ javaOpts ++
        Seq("-cp", goronClasspath, "goron.GoronCli") ++ args

      log.debug(s"Running: ${cmd.mkString(" ")}")

      val exitCode = scala.sys.process.Process(cmd).!
      if (exitCode != 0)
        sys.error(s"goron optimization failed with exit code $exitCode")

      log.info(s"Optimized jar: ${outputJar.getAbsolutePath}")
      outputJar
    }
  )
}
