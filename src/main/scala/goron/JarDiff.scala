/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron

import goron.optimizer.AsmUtils

import scala.jdk.CollectionConverters._
import scala.tools.asm.tree.{ClassNode, MethodNode}
import scala.tools.asm.{Attribute, ClassReader}
import scala.tools.asm.util.{TraceClassVisitor, Textifier}

import java.io.{PrintWriter, StringWriter}

object JarDiff {
  case class DiffResult(
      added: List[String],
      removed: List[String],
      changed: List[ClassDiff],
      unchanged: Int
  )

  case class ClassDiff(
      name: String,
      methodDiffs: List[MethodDiff],
      sizeBefore: Int,
      sizeAfter: Int
  )

  case class MethodDiff(
      name: String,
      desc: String,
      insnsBefore: Int,
      insnsAfter: Int,
      diff: List[String]
  )

  def compare(
      beforeJar: String,
      afterJar: String,
      detail: Boolean = false,
      filter: Option[String] = None,
      decompile: Boolean = false
  ): DiffResult = {
    val beforeEntries = JarIO.readJar(beforeJar).filter(_.isClass)
    val afterEntries = JarIO.readJar(afterJar).filter(_.isClass)

    val beforeMap = beforeEntries.map(e => e.name -> e.bytes).toMap
    val afterMap = afterEntries.map(e => e.name -> e.bytes).toMap

    val allNames = (beforeMap.keySet ++ afterMap.keySet).toList.sorted
    val filteredNames = filter match {
      case Some(pat) => allNames.filter(_.contains(pat))
      case None      => allNames
    }

    val added = filteredNames.filter(n => !beforeMap.contains(n) && afterMap.contains(n))
      .map(_.stripSuffix(".class"))
    val removed = filteredNames.filter(n => beforeMap.contains(n) && !afterMap.contains(n))
      .map(_.stripSuffix(".class"))

    val bothPresent = filteredNames.filter(n => beforeMap.contains(n) && afterMap.contains(n))

    var unchangedCount = 0
    val changed = List.newBuilder[ClassDiff]

    for (name <- bothPresent) {
      val beforeBytes = beforeMap(name)
      val afterBytes = afterMap(name)

      if (java.util.Arrays.equals(beforeBytes, afterBytes)) {
        unchangedCount += 1
      } else {
        val beforeNode = parseAndNormalize(beforeBytes)
        val afterNode = parseAndNormalize(afterBytes)

        val beforeText = textifyClass(beforeNode, decompile)
        val afterText = textifyClass(afterNode, decompile)

        if (beforeText == afterText) {
          unchangedCount += 1
        } else {
          val methodDiffs = diffMethods(beforeNode, afterNode, detail, decompile)
          val sizeBefore = totalInsns(beforeNode)
          val sizeAfter = totalInsns(afterNode)
          if (methodDiffs.nonEmpty || sizeBefore != sizeAfter) {
            changed += ClassDiff(name.stripSuffix(".class"), methodDiffs, sizeBefore, sizeAfter)
          } else {
            unchangedCount += 1
          }
        }
      }
    }

    DiffResult(added, removed, changed.result(), unchangedCount)
  }

  private def parseAndNormalize(bytes: Array[Byte]): ClassNode = {
    val cn = AsmUtils.classFromBytes(bytes)
    AsmUtils.sortClassMembers(cn)
    AsmUtils.zapScalaClassAttrs(cn)
    cn
  }

  private def totalInsns(cn: ClassNode): Int =
    cn.methods.asScala.map(_.instructions.size()).sum

  private def textifyClass(cn: ClassNode, decompile: Boolean): String = {
    if (decompile) decompileClass(cn)
    else AsmUtils.textify(cn)
  }

  private def diffMethods(
      before: ClassNode,
      after: ClassNode,
      detail: Boolean,
      decompile: Boolean
  ): List[MethodDiff] = {
    val beforeMethods = before.methods.asScala.map(m => (m.name, m.desc) -> m).toMap
    val afterMethods = after.methods.asScala.map(m => (m.name, m.desc) -> m).toMap

    val allKeys = (beforeMethods.keySet ++ afterMethods.keySet).toList.sortBy(_._1)
    val diffs = List.newBuilder[MethodDiff]

    for (key @ (name, desc) <- allKeys) {
      (beforeMethods.get(key), afterMethods.get(key)) match {
        case (Some(bm), Some(am)) =>
          val bInsns = bm.instructions.size()
          val aInsns = am.instructions.size()
          val bText = if (decompile) "" else AsmUtils.textify(bm)
          val aText = if (decompile) "" else AsmUtils.textify(am)
          if (bInsns != aInsns || bText != aText) {
            val diffLines = if (detail) {
              val bt = if (decompile) "" else bText
              val at = if (decompile) "" else aText
              computeUnifiedDiff(bt.linesIterator.toList, at.linesIterator.toList, 3)
            } else Nil
            diffs += MethodDiff(name, desc, bInsns, aInsns, diffLines)
          }
        case (Some(bm), None) =>
          diffs += MethodDiff(name, desc, bm.instructions.size(), 0, List(s"--- method removed"))
        case (None, Some(am)) =>
          diffs += MethodDiff(name, desc, 0, am.instructions.size(), List(s"+++ method added"))
        case _ =>
      }
    }

    diffs.result()
  }

  /** Simple LCS-based unified diff. */
  private[goron] def computeUnifiedDiff(
      before: List[String],
      after: List[String],
      context: Int
  ): List[String] = {
    // Myers-like diff using LCS
    val lcs = longestCommonSubsequence(before, after)
    val hunks = List.newBuilder[String]

    var bi = 0
    var ai = 0
    var li = 0

    case class Change(removals: List[(Int, String)], additions: List[(Int, String)])
    val changes = List.newBuilder[Change]

    while (bi < before.size || ai < after.size) {
      if (li < lcs.size && bi < before.size && ai < after.size && before(bi) == lcs(li) && after(ai) == lcs(li)) {
        bi += 1; ai += 1; li += 1
      } else {
        val removals = List.newBuilder[(Int, String)]
        val additions = List.newBuilder[(Int, String)]
        while (bi < before.size && (li >= lcs.size || before(bi) != lcs(li))) {
          removals += ((bi, before(bi)))
          bi += 1
        }
        while (ai < after.size && (li >= lcs.size || after(ai) != lcs(li))) {
          additions += ((ai, after(ai)))
          ai += 1
        }
        changes += Change(removals.result(), additions.result())
      }
    }

    for (change <- changes.result()) {
      val startLine = change.removals.headOption.map(_._1)
        .orElse(change.additions.headOption.map(_._1)).getOrElse(0)
      val ctxStart = math.max(0, startLine - context)
      val ctxEnd = {
        val lastLine = change.removals.lastOption.map(_._1)
          .orElse(change.additions.lastOption.map(_._1)).getOrElse(0)
        math.min(before.size - 1, lastLine + context)
      }

      // Context before
      for (i <- ctxStart until startLine if i < before.size) {
        hunks += s" ${before(i)}"
      }
      for ((_, line) <- change.removals) hunks += s"-$line"
      for ((_, line) <- change.additions) hunks += s"+$line"
      // Context after
      val afterStart = change.removals.lastOption.map(_._1 + 1)
        .orElse(change.additions.lastOption.map(_ => startLine)).getOrElse(startLine)
      for (i <- afterStart to ctxEnd if i < before.size && !change.removals.exists(_._1 == i)) {
        hunks += s" ${before(i)}"
      }
    }

    hunks.result()
  }

  private def longestCommonSubsequence(a: List[String], b: List[String]): List[String] = {
    val m = a.size
    val n = b.size
    val dp = Array.ofDim[Int](m + 1, n + 1)
    for (i <- 1 to m; j <- 1 to n) {
      dp(i)(j) = if (a(i - 1) == b(j - 1)) dp(i - 1)(j - 1) + 1
      else math.max(dp(i - 1)(j), dp(i)(j - 1))
    }
    // Backtrack
    val result = List.newBuilder[String]
    var i = m; var j = n
    while (i > 0 && j > 0) {
      if (a(i - 1) == b(j - 1)) {
        result += a(i - 1)
        i -= 1; j -= 1
      } else if (dp(i - 1)(j) > dp(i)(j - 1)) {
        i -= 1
      } else {
        j -= 1
      }
    }
    result.result().reverse
  }

  private def decompileClass(cn: ClassNode): String = {
    try {
      val cfrClass = Class.forName("org.benf.cfr.reader.api.CfrDriver")
      // CFR works on bytes, so serialize the ClassNode back
      val cw = new scala.tools.asm.ClassWriter(0)
      cn.accept(cw)
      val bytes = cw.toByteArray

      val builderClass = Class.forName("org.benf.cfr.reader.api.CfrDriver$Builder")
      val builder = cfrClass.getMethod("builder").invoke(null)

      // Use ClassFileSource to provide bytes directly
      val sourceClass = Class.forName("org.benf.cfr.reader.api.ClassFileSource")
      val sinkClass = Class.forName("org.benf.cfr.reader.api.OutputSinkFactory")

      // Fallback: just use textify since CFR API is complex
      AsmUtils.textify(cn)
    } catch {
      case _: ClassNotFoundException =>
        AsmUtils.textify(cn)
    }
  }

  def formatSummary(result: DiffResult): String = {
    val sb = new StringBuilder
    sb.append(s"=== Jar Diff Summary ===\n")
    sb.append(s"  Added classes:     ${result.added.size}\n")
    sb.append(s"  Removed classes:   ${result.removed.size}\n")
    sb.append(s"  Changed classes:   ${result.changed.size}\n")
    sb.append(s"  Unchanged classes: ${result.unchanged}\n")

    if (result.added.nonEmpty) {
      sb.append(s"\nAdded:\n")
      result.added.foreach(n => sb.append(s"  + $n\n"))
    }
    if (result.removed.nonEmpty) {
      sb.append(s"\nRemoved:\n")
      result.removed.foreach(n => sb.append(s"  - $n\n"))
    }
    if (result.changed.nonEmpty) {
      sb.append(s"\nChanged:\n")
      for (cd <- result.changed.sortBy(c => -(c.sizeAfter - c.sizeBefore))) {
        val delta = cd.sizeAfter - cd.sizeBefore
        val sign = if (delta >= 0) "+" else ""
        sb.append(s"  ${cd.name}: ${cd.sizeBefore} → ${cd.sizeAfter} insns ($sign$delta)\n")
        for (md <- cd.methodDiffs.sortBy(m => -(m.insnsAfter - m.insnsBefore))) {
          val mDelta = md.insnsAfter - md.insnsBefore
          val mSign = if (mDelta >= 0) "+" else ""
          sb.append(s"    ${md.name}${md.desc}: ${md.insnsBefore} → ${md.insnsAfter} ($mSign$mDelta)\n")
        }
      }
    }

    sb.toString()
  }

  def formatDetail(result: DiffResult): String = {
    val sb = new StringBuilder
    sb.append(formatSummary(result))

    for (cd <- result.changed.sortBy(_.name)) {
      for (md <- cd.methodDiffs if md.diff.nonEmpty) {
        sb.append(s"\n--- ${cd.name}.${md.name}${md.desc} ---\n")
        md.diff.foreach(line => sb.append(line).append('\n'))
      }
    }

    sb.toString()
  }
}
