# TODO

- [ ] Eliminate unused methods from retained classes (currently only whole-class DCE)
- [ ] Improve CLI output: show progress and stats during optimization (classes parsed, reachable, inlined, eliminated, etc.)
- [ ] Create an sbt plugin
- [ ] Make closed-world analysis update InlineInfo so the inliner can exploit effectively-final methods on Scala classes (currently only sets ACC_FINAL, which the inliner ignores in favor of ScalaInlineInfo's effectivelyFinal)
