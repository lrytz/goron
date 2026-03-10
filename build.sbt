lazy val goron = (project in file("."))
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "goron",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "2.13.18",
    organization := "org.scala-lang",
    startYear := Some(2026),
    licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0")),
    headerLicense := Some(HeaderLicense.Custom(
      s"""Goron, a link-time optimizer for Scala JVM bytecode
         |Copyright EPFL and Lightbend, Inc. dba Akka
         |Licensed under Apache License 2.0
         |(http://www.apache.org/licenses/LICENSE-2.0)
         |""".stripMargin
    )),
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" % "scala-asm" % "9.9.0-scala-1",
      "org.scalameta" %% "munit" % "1.2.4" % Test,
      "org.scala-lang" % "scala-compiler" % scalaVersion.value % Test,
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    Compile / mainClass := Some("goron.GoronCli"),
    assembly / mainClass := Some("goron.GoronCli"),
    assembly / assemblyJarName := "goron.jar",
    scalacOptions ++= Seq(
      "-deprecation", "-feature", "-unchecked", "-Werror",
      // Suppress warning inherent to path-dependent types in forked compiler code
      "-Wconf:msg=The outer reference in this type test cannot be checked at run time:s",
    ),
  )

lazy val bench = (project in file("bench"))
  .enablePlugins(JmhPlugin)
  .dependsOn(goron)
  .settings(
    name := "goron-bench",
    scalaVersion := "2.13.18",
    // Don't publish benchmarks
    publish / skip := true,
    // JMH needs forked JVM for accurate measurements
    Jmh / javaOptions ++= Seq("-Xmx4g", "-Xms4g"),
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-compiler" % scalaVersion.value,
    ),
  )
