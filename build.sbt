lazy val goron = (project in file("."))
  .settings(
    name := "goron",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "2.13.18",
    libraryDependencies ++= Seq(
      "org.scala-lang.modules" % "scala-asm" % "9.9.0-scala-1",
      "org.scalameta" %% "munit" % "1.2.4" % Test,
      "org.scala-lang" % "scala-compiler" % scalaVersion.value % Test,
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    Compile / mainClass := Some("goron.GoronCli"),
    assembly / mainClass := Some("goron.GoronCli"),
    assembly / assemblyJarName := "goron.jar",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
  )
