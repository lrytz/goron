package goron

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.tools.asm.{Handle, Opcodes, Type}
import scala.tools.asm.tree._

/**
 * Whole-program reachability analysis. Starting from entry point classes,
 * performs BFS to discover all reachable classes by following:
 * - Method invocations (INVOKE*)
 * - Field accesses
 * - Type references in descriptors, signatures, annotations
 * - Super class and interface chains
 * - Static initializers of referenced classes
 * - Bootstrap method references (invokedynamic)
 */
object ReachabilityAnalysis {

  /**
   * Compute the set of reachable class internal names, starting from the given entry points.
   * @param classNodes all classes in the program
   * @param entryPoints internal names of entry point classes (all their methods are roots)
   * @return set of reachable class internal names
   */
  def reachableClasses(
    classNodes: Iterable[ClassNode],
    entryPoints: Set[String]
  ): Set[String] = {
    val classByName = classNodes.map(cn => cn.name -> cn).toMap
    val reachable = mutable.Set.empty[String]
    val worklist = mutable.Queue.empty[String]

    // Seed with entry points
    for (ep <- entryPoints if classByName.contains(ep)) {
      reachable += ep
      worklist += ep
    }

    // Also seed with classes referenced from META-INF/services (handled by caller)
    // and classes with static initializers that have side effects (conservative: all entry points)

    while (worklist.nonEmpty) {
      val className = worklist.dequeue()
      classByName.get(className) match {
        case Some(cn) =>
          // Process all references in this class
          val refs = collectReferences(cn)
          for (ref <- refs if !reachable.contains(ref) && classByName.contains(ref)) {
            reachable += ref
            worklist += ref
          }
        case None =>
          // External class (JDK, library) — not in our classpath, skip
      }
    }

    reachable.toSet
  }

  /** Collect all class internal names referenced by a ClassNode. */
  private def collectReferences(cn: ClassNode): Set[String] = {
    val refs = mutable.Set.empty[String]

    // Super class and interfaces
    if (cn.superName != null) refs += cn.superName
    if (cn.interfaces != null) cn.interfaces.asScala.foreach(refs += _)

    // Inner classes
    if (cn.innerClasses != null) {
      cn.innerClasses.asScala.foreach { ic =>
        if (ic.outerName == cn.name || ic.name.startsWith(cn.name + "$")) {
          refs += ic.name
        }
      }
    }

    // Fields
    if (cn.fields != null) {
      cn.fields.asScala.foreach { fn =>
        addTypeDescRefs(fn.desc, refs)
        if (fn.signature != null) addSignatureRefs(fn.signature, refs)
      }
    }

    // Methods
    if (cn.methods != null) {
      cn.methods.asScala.foreach { mn =>
        addMethodDescRefs(mn.desc, refs)
        if (mn.signature != null) addSignatureRefs(mn.signature, refs)

        // Exception types
        if (mn.exceptions != null) mn.exceptions.asScala.foreach(refs += _)

        // Instructions
        if (mn.instructions != null) {
          val iter = mn.instructions.iterator()
          while (iter.hasNext) {
            iter.next() match {
              case mi: MethodInsnNode =>
                refs += mi.owner
                addMethodDescRefs(mi.desc, refs)
              case fi: FieldInsnNode =>
                refs += fi.owner
                addTypeDescRefs(fi.desc, refs)
              case ti: TypeInsnNode =>
                addInternalOrArrayRef(ti.desc, refs)
              case mri: MultiANewArrayInsnNode =>
                addTypeDescRefs(mri.desc, refs)
              case ldc: LdcInsnNode =>
                ldc.cst match {
                  case t: Type =>
                    if (t.getSort == Type.OBJECT) refs += t.getInternalName
                    else if (t.getSort == Type.ARRAY) addTypeDescRefs(t.getDescriptor, refs)
                    else if (t.getSort == Type.METHOD) addMethodDescRefs(t.getDescriptor, refs)
                  case h: Handle =>
                    refs += h.getOwner
                    addMethodDescRefs(h.getDesc, refs)
                  case _ => // primitives, strings
                }
              case inv: InvokeDynamicInsnNode =>
                addMethodDescRefs(inv.desc, refs)
                if (inv.bsm != null) {
                  refs += inv.bsm.getOwner
                  addMethodDescRefs(inv.bsm.getDesc, refs)
                }
                if (inv.bsmArgs != null) {
                  for (arg <- inv.bsmArgs) arg match {
                    case t: Type =>
                      if (t.getSort == Type.OBJECT) refs += t.getInternalName
                      else if (t.getSort == Type.METHOD) addMethodDescRefs(t.getDescriptor, refs)
                    case h: Handle =>
                      refs += h.getOwner
                      addMethodDescRefs(h.getDesc, refs)
                    case _ =>
                  }
                }
              case _ =>
            }
          }
        }

        // Try-catch handler types
        if (mn.tryCatchBlocks != null) {
          mn.tryCatchBlocks.asScala.foreach { tcb =>
            if (tcb.`type` != null) refs += tcb.`type`
          }
        }
      }
    }

    // Annotations on the class
    addAnnotationRefs(cn.visibleAnnotations, refs)
    addAnnotationRefs(cn.invisibleAnnotations, refs)

    refs.toSet
  }

  /** Add class refs from a type descriptor (e.g. "Ljava/lang/String;", "[I", "I") */
  private def addTypeDescRefs(desc: String, refs: mutable.Set[String]): Unit = {
    if (desc == null || desc.isEmpty) return
    var i = 0
    while (i < desc.length) {
      desc.charAt(i) match {
        case 'L' =>
          val end = desc.indexOf(';', i)
          if (end > i) {
            refs += desc.substring(i + 1, end)
            i = end + 1
          } else return
        case '[' => i += 1
        case _ => return
      }
    }
  }

  /** Add class refs from a method descriptor */
  private def addMethodDescRefs(desc: String, refs: mutable.Set[String]): Unit = {
    if (desc == null) return
    try {
      val mt = Type.getMethodType(desc)
      for (at <- mt.getArgumentTypes) {
        if (at.getSort == Type.OBJECT) refs += at.getInternalName
        else if (at.getSort == Type.ARRAY) addTypeFromAsmType(at.getElementType, refs)
      }
      val rt = mt.getReturnType
      if (rt.getSort == Type.OBJECT) refs += rt.getInternalName
      else if (rt.getSort == Type.ARRAY) addTypeFromAsmType(rt.getElementType, refs)
    } catch {
      case _: Exception => // malformed descriptor, skip
    }
  }

  private def addTypeFromAsmType(t: Type, refs: mutable.Set[String]): Unit = {
    if (t.getSort == Type.OBJECT) refs += t.getInternalName
    else if (t.getSort == Type.ARRAY) addTypeFromAsmType(t.getElementType, refs)
  }

  /** Handle TypeInsnNode operands which may be internal names or array descriptors */
  private def addInternalOrArrayRef(desc: String, refs: mutable.Set[String]): Unit = {
    if (desc.startsWith("[")) addTypeDescRefs(desc, refs)
    else refs += desc
  }

  /** Add class refs from generic signatures */
  private def addSignatureRefs(sig: String, refs: mutable.Set[String]): Unit = {
    // Simple scan: find all L....; patterns
    var i = 0
    while (i < sig.length) {
      if (sig.charAt(i) == 'L') {
        val end = sig.indexWhere(c => c == ';' || c == '<', i + 1)
        if (end > i) {
          refs += sig.substring(i + 1, end).replace('.', '/')
          i = end
        } else return
      } else {
        i += 1
      }
    }
  }

  /** Add class refs from annotations */
  private def addAnnotationRefs(annotations: java.util.List[AnnotationNode], refs: mutable.Set[String]): Unit = {
    if (annotations == null) return
    annotations.asScala.foreach { an =>
      addTypeDescRefs(an.desc, refs)
    }
  }
}
