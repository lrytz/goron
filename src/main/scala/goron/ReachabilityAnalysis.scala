package goron

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.tools.asm.{Handle, Opcodes, Type}
import scala.tools.asm.tree._

/**
 * Whole-program reachability analysis. Starting from entry point classes,
 * performs BFS at method granularity to discover reachable classes.
 *
 * Method-level analysis means that if a class has 100 methods but only 1 is
 * called, only that method's references are followed — not the other 99.
 * This dramatically reduces the reachable set for programs using large libraries.
 *
 * References followed:
 * - Method invocations (INVOKE*)
 * - Field accesses (trigger class reachability + field type refs)
 * - Type references in instructions (NEW, CHECKCAST, etc.)
 * - Super class and interface chains
 * - Static initializers (<clinit>) of any newly reachable class
 * - Bootstrap method references (invokedynamic)
 */
object ReachabilityAnalysis {

  /**
   * Compute the set of reachable class internal names, starting from the given entry points.
   * Uses method-level granularity: only methods that are actually called contribute references.
   *
   * @param classNodes all classes in the program
   * @param entryPoints internal names of entry point classes (all their methods are roots)
   * @return set of reachable class internal names
   */
  def reachableClasses(
    classNodes: Iterable[ClassNode],
    entryPoints: Set[String]
  ): Set[String] = {
    val classByName = classNodes.map(cn => cn.name -> cn).toMap

    // Build subclass map for virtual dispatch resolution
    val subclasses = mutable.Map.empty[String, mutable.Set[String]]
    for (cn <- classNodes) {
      if (cn.superName != null)
        subclasses.getOrElseUpdate(cn.superName, mutable.Set.empty) += cn.name
      if (cn.interfaces != null)
        cn.interfaces.asScala.foreach(iface =>
          subclasses.getOrElseUpdate(iface, mutable.Set.empty) += cn.name)
    }

    val reachableClasses = mutable.Set.empty[String]
    val reachableMethods = mutable.Set.empty[(String, String, String)] // (class, name, desc)
    val classWorklist = mutable.Queue.empty[String]
    val methodWorklist = mutable.Queue.empty[(String, String, String)]

    def enqueueClass(name: String): Unit = {
      if (!reachableClasses.contains(name) && classByName.contains(name)) {
        reachableClasses += name
        classWorklist += name
      }
    }

    def enqueueMethod(owner: String, name: String, desc: String): Unit = {
      val key = (owner, name, desc)
      if (!reachableMethods.contains(key)) {
        // Only enqueue if the method actually exists in this class
        classByName.get(owner) match {
          case Some(cn) if cn.methods != null && cn.methods.asScala.exists(m => m.name == name && m.desc == desc) =>
            reachableMethods += key
            methodWorklist += key
            enqueueClass(owner)
          case _ => // method not found in this class, skip
        }
      }
    }

    // When a virtual/interface call targets (owner, name, desc), we need to also mark
    // overrides in reachable subclasses, AND mark overrides in subclasses that become
    // reachable later. Track virtual call targets for this purpose.
    val virtualCallTargets = mutable.Set.empty[(String, String, String)]

    def enqueueVirtualCall(owner: String, name: String, desc: String): Unit = {
      virtualCallTargets += ((owner, name, desc))
      // Mark the method in the declared class (if it exists)
      enqueueMethod(owner, name, desc)
      // Mark overrides in all currently-reachable subclasses
      enqueueOverridesInSubclasses(owner, name, desc)
    }

    def enqueueOverridesInSubclasses(owner: String, name: String, desc: String): Unit = {
      val subs = subclasses.getOrElse(owner, mutable.Set.empty)
      for (sub <- subs) {
        // If this subclass is reachable, check for override
        if (reachableClasses.contains(sub)) {
          enqueueMethod(sub, name, desc)
        }
        // Continue recursively through sub-subclasses
        enqueueOverridesInSubclasses(sub, name, desc)
      }
    }

    // Seed: all methods in entry point classes
    for (ep <- entryPoints) {
      classByName.get(ep).foreach { cn =>
        enqueueClass(ep)
        if (cn.methods != null)
          cn.methods.asScala.foreach(mn => enqueueMethod(ep, mn.name, mn.desc))
      }
    }

    // Main BFS loop
    while (classWorklist.nonEmpty || methodWorklist.nonEmpty) {
      // Process newly reachable classes
      while (classWorklist.nonEmpty) {
        val className = classWorklist.dequeue()
        classByName.get(className).foreach { cn =>
          // Superclass and interfaces always reachable
          if (cn.superName != null) enqueueClass(cn.superName)
          if (cn.interfaces != null) cn.interfaces.asScala.foreach(enqueueClass)

          // <clinit> is always reachable when a class is reached
          if (cn.methods != null) {
            cn.methods.asScala
              .filter(mn => mn.name == "<clinit>")
              .foreach(mn => enqueueMethod(className, mn.name, mn.desc))
          }

          // Field types: field declarations make types reachable (for field access resolution)
          if (cn.fields != null) {
            cn.fields.asScala.foreach { fn =>
              addTypeDescClassRefs(fn.desc, enqueueClass)
            }
          }

          // Class-level annotations
          addAnnotationClassRefs(cn.visibleAnnotations, enqueueClass)
          addAnnotationClassRefs(cn.invisibleAnnotations, enqueueClass)

          // If this newly-reachable class overrides any virtual call target, mark the override
          for ((targetOwner, name, desc) <- virtualCallTargets) {
            if (isSubclassOf(className, targetOwner, classByName))
              enqueueMethod(className, name, desc)
          }
        }
      }

      // Process reachable methods
      while (methodWorklist.nonEmpty) {
        val (className, methodName, methodDesc) = methodWorklist.dequeue()
        classByName.get(className).foreach { cn =>
          cn.methods.asScala
            .find(mn => mn.name == methodName && mn.desc == methodDesc)
            .foreach { mn =>
              processMethodRefs(mn, className, enqueueClass, enqueueMethod, enqueueVirtualCall)
            }
        }
      }
    }

    reachableClasses.toSet
  }

  /** Check if `child` is a (transitive) subclass/subinterface of `parent`. */
  private def isSubclassOf(child: String, parent: String, classByName: Map[String, ClassNode]): Boolean = {
    if (child == parent) return true
    classByName.get(child) match {
      case Some(cn) =>
        (cn.superName != null && isSubclassOf(cn.superName, parent, classByName)) ||
        (cn.interfaces != null && cn.interfaces.asScala.exists(i => isSubclassOf(i, parent, classByName)))
      case None => false
    }
  }

  /** Process all references within a method body. */
  private def processMethodRefs(
    mn: MethodNode,
    owner: String,
    enqueueClass: String => Unit,
    enqueueMethod: (String, String, String) => Unit,
    enqueueVirtualCall: (String, String, String) => Unit,
  ): Unit = {
    // Method descriptor: parameter and return types
    addMethodDescClassRefs(mn.desc, enqueueClass)

    // Exception types in throws clause
    if (mn.exceptions != null) mn.exceptions.asScala.foreach(enqueueClass)

    // Instructions
    if (mn.instructions != null) {
      val iter = mn.instructions.iterator()
      while (iter.hasNext) {
        iter.next() match {
          case mi: MethodInsnNode =>
            enqueueClass(mi.owner)
            addMethodDescClassRefs(mi.desc, enqueueClass)
            mi.getOpcode match {
              case Opcodes.INVOKESTATIC =>
                enqueueMethod(mi.owner, mi.name, mi.desc)
              case Opcodes.INVOKESPECIAL =>
                // Constructor or super call — resolve directly
                enqueueMethod(mi.owner, mi.name, mi.desc)
              case Opcodes.INVOKEVIRTUAL | Opcodes.INVOKEINTERFACE =>
                enqueueVirtualCall(mi.owner, mi.name, mi.desc)
              case _ =>
            }

          case fi: FieldInsnNode =>
            enqueueClass(fi.owner)
            addTypeDescClassRefs(fi.desc, enqueueClass)

          case ti: TypeInsnNode =>
            addInternalOrArrayClassRef(ti.desc, enqueueClass)

          case mri: MultiANewArrayInsnNode =>
            addTypeDescClassRefs(mri.desc, enqueueClass)

          case ldc: LdcInsnNode =>
            ldc.cst match {
              case t: Type =>
                if (t.getSort == Type.OBJECT) enqueueClass(t.getInternalName)
                else if (t.getSort == Type.ARRAY) addTypeDescClassRefs(t.getDescriptor, enqueueClass)
                else if (t.getSort == Type.METHOD) addMethodDescClassRefs(t.getDescriptor, enqueueClass)
              case h: Handle =>
                enqueueClass(h.getOwner)
                addMethodDescClassRefs(h.getDesc, enqueueClass)
                // Handle refs point to actual method implementations
                enqueueMethod(h.getOwner, h.getName, h.getDesc)
              case _ => // primitives, strings
            }

          case inv: InvokeDynamicInsnNode =>
            addMethodDescClassRefs(inv.desc, enqueueClass)
            if (inv.bsm != null) {
              enqueueClass(inv.bsm.getOwner)
              addMethodDescClassRefs(inv.bsm.getDesc, enqueueClass)
              enqueueMethod(inv.bsm.getOwner, inv.bsm.getName, inv.bsm.getDesc)
            }
            if (inv.bsmArgs != null) {
              for (arg <- inv.bsmArgs) arg match {
                case t: Type =>
                  if (t.getSort == Type.OBJECT) enqueueClass(t.getInternalName)
                  else if (t.getSort == Type.METHOD) addMethodDescClassRefs(t.getDescriptor, enqueueClass)
                case h: Handle =>
                  enqueueClass(h.getOwner)
                  addMethodDescClassRefs(h.getDesc, enqueueClass)
                  enqueueMethod(h.getOwner, h.getName, h.getDesc)
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
        if (tcb.`type` != null) enqueueClass(tcb.`type`)
      }
    }
  }

  /** Add class refs from a type descriptor (e.g. "Ljava/lang/String;", "[I", "I") */
  private def addTypeDescClassRefs(desc: String, enqueue: String => Unit): Unit = {
    if (desc == null || desc.isEmpty) return
    var i = 0
    while (i < desc.length) {
      desc.charAt(i) match {
        case 'L' =>
          val end = desc.indexOf(';', i)
          if (end > i) {
            enqueue(desc.substring(i + 1, end))
            i = end + 1
          } else return
        case '[' => i += 1
        case _ => return
      }
    }
  }

  /** Add class refs from a method descriptor */
  private def addMethodDescClassRefs(desc: String, enqueue: String => Unit): Unit = {
    if (desc == null) return
    try {
      val mt = Type.getMethodType(desc)
      for (at <- mt.getArgumentTypes) {
        if (at.getSort == Type.OBJECT) enqueue(at.getInternalName)
        else if (at.getSort == Type.ARRAY) addTypeFromAsmType(at.getElementType, enqueue)
      }
      val rt = mt.getReturnType
      if (rt.getSort == Type.OBJECT) enqueue(rt.getInternalName)
      else if (rt.getSort == Type.ARRAY) addTypeFromAsmType(rt.getElementType, enqueue)
    } catch {
      case _: Exception => // malformed descriptor, skip
    }
  }

  private def addTypeFromAsmType(t: Type, enqueue: String => Unit): Unit = {
    if (t.getSort == Type.OBJECT) enqueue(t.getInternalName)
    else if (t.getSort == Type.ARRAY) addTypeFromAsmType(t.getElementType, enqueue)
  }

  /** Handle TypeInsnNode operands which may be internal names or array descriptors */
  private def addInternalOrArrayClassRef(desc: String, enqueue: String => Unit): Unit = {
    if (desc.startsWith("[")) addTypeDescClassRefs(desc, enqueue)
    else enqueue(desc)
  }

  /** Add class refs from annotations */
  private def addAnnotationClassRefs(annotations: java.util.List[AnnotationNode], enqueue: String => Unit): Unit = {
    if (annotations == null) return
    annotations.asScala.foreach { an =>
      addTypeDescClassRefs(an.desc, enqueue)
    }
  }
}
