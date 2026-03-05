package goron

object Goron {
  def run(config: GoronConfig): Unit = {
    if (config.verbose) println(s"Reading ${config.inputJars.size} input jar(s)...")

    val allEntries = JarIO.readJars(config.inputJars)
    val classEntries = allEntries.filter(_.isClass)
    val resourceEntries = allEntries.filterNot(_.isClass)

    if (config.verbose) {
      println(s"  ${classEntries.size} class files")
      println(s"  ${resourceEntries.size} resource files")
    }

    // Phase 1: passthrough — just write everything back out
    // Later phases will add optimization here

    if (config.verbose) println(s"Writing output jar: ${config.outputJar}")
    JarIO.writeJar(config.outputJar, classEntries ++ resourceEntries)
    if (config.verbose) println("Done.")
  }
}
