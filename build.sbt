lazy val goron = (project in file("."))
  .settings(
    name := "goron",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "2.13.16",
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" % "scala-asm" % "9.7.1-scala-1",
      "com.lihaoyi" %% "utest" % "0.8.4" % Test,
    ),
    testFrameworks += new TestFramework("utest.runner.Framework"),
    Compile / mainClass := Some("goron.GoronCli"),
    assembly / mainClass := Some("goron.GoronCli"),
    assembly / assemblyJarName := "goron.jar",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
  )
