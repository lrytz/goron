lazy val `sbt-goron` = (project in file("."))
  .settings(
    name := "sbt-goron",
    organization := "org.scala-lang",
    version := "0.1.0-SNAPSHOT",
    sbtPlugin := true,
    addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1"),
    scalacOptions ++= Seq("-deprecation", "-feature"),
  )
