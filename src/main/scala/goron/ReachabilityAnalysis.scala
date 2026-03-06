package goron

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.tools.asm
import scala.tools.asm.{Handle, Opcodes, Type}
import scala.tools.asm.tree._

/**
 * Whole-program reachability analysis in two phases:
 *
 * Phase 1 (method-level BFS): Starting from entry point classes, follows method
 * calls at method granularity. If a class has 100 methods but only 1 is called,
 * only that method's references are followed. This determines the "execution-reachable"
 * set — classes whose code will actually run.
 *
 * Phase 2 (load closure): For each execution-reachable class, ensures all classes
 * referenced anywhere in its classfile (method bodies, descriptors, constant pool)
 * are also present. These "load-reachable" classes are needed for JVM class loading
 * and verification, but their own method bodies are NOT traversed — only their
 * type hierarchy and descriptor types are followed transitively.
 *
 * This two-phase approach eliminates unreferenced classes aggressively while
 * ensuring retained classes can actually be loaded by the JVM.
 */
object ReachabilityAnalysis {

  /**
   * Compute the set of reachable class internal names, starting from the given entry points.
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
    val (execReachable, reachableMethods) = methodLevelBFS(classByName, entryPoints)
    loadClosure(execReachable, reachableMethods, classByName)
  }

  /**
   * Like `reachableClasses`, but also returns the set of reachable methods and
   * the execution-reachable class set (before load closure).
   *
   * @return (allReachableClasses, execReachableClasses, reachableMethods)
   */
  def reachableClassesAndMethods(
    classNodes: Iterable[ClassNode],
    entryPoints: Set[String]
  ): (Set[String], Set[String], Set[(String, String, String)]) = {
    val classByName = classNodes.map(cn => cn.name -> cn).toMap
    val (execReachable, reachableMethods) = methodLevelBFS(classByName, entryPoints)
    val allReachable = loadClosure(execReachable, reachableMethods, classByName)
    (allReachable, execReachable, reachableMethods)
  }

  /**
   * Remove unreachable methods from ClassNodes in-place.
   * Only strips from execution-reachable classes (not load-closure-only).
   * Never strips abstract, native, or bridge methods, nor methods that
   * override/implement methods from classes outside the analyzed set
   * (e.g. JDK classes whose method bodies we can't trace).
   */
  def stripUnreachableMethods(
    classNodes: Iterable[ClassNode],
    reachableMethods: Set[(String, String, String)],
    execReachableClasses: Set[String],
  ): Int = {
    var stripped = 0
    for (cn <- classNodes if execReachableClasses.contains(cn.name)) {
      if (cn.methods != null) {
        val iter = cn.methods.iterator()
        while (iter.hasNext) {
          val mn = iter.next()
          val isAbstract = (mn.access & Opcodes.ACC_ABSTRACT) != 0
          val isNative = (mn.access & Opcodes.ACC_NATIVE) != 0
          val isBridge = (mn.access & Opcodes.ACC_BRIDGE) != 0
          if (!isAbstract && !isNative && !isBridge && !reachableMethods.contains((cn.name, mn.name, mn.desc))) {
            iter.remove()
            stripped += 1
          }
        }
      }
    }
    stripped
  }

  /** Collect method signatures from an external class and its supertypes. */
  private def collectExternalClassMethods(internalName: String): Set[(String, String)] = {
    collectExternalClassMethodsCached.getOrElseUpdate(internalName, {
      try {
        val stream = Thread.currentThread().getContextClassLoader
          .getResourceAsStream(internalName + ".class")
        if (stream == null) return Set.empty
        val bytes = try stream.readAllBytes() finally stream.close()
        val cr = new asm.ClassReader(bytes)
        val methods = mutable.Set.empty[(String, String)]
        val superNames = mutable.ListBuffer.empty[String]
        cr.accept(new asm.ClassVisitor(Opcodes.ASM9) {
          override def visit(version: Int, access: Int, name: String, signature: String,
              superName: String, interfaces: Array[String]): Unit = {
            if (superName != null) superNames += superName
            if (interfaces != null) superNames ++= interfaces
          }
          override def visitMethod(access: Int, name: String, descriptor: String,
              signature: String, exceptions: Array[String]): asm.MethodVisitor = {
            methods += ((name, descriptor))
            null
          }
        }, asm.ClassReader.SKIP_CODE | asm.ClassReader.SKIP_DEBUG | asm.ClassReader.SKIP_FRAMES)
        methods.toSet ++ superNames.flatMap(collectExternalClassMethods)
      } catch {
        case _: Exception => Set.empty
      }
    })
  }

  private val collectExternalClassMethodsCached = mutable.Map.empty[String, Set[(String, String)]]

  // ---------------------------------------------------------------------------
  // Phase 1: Method-level BFS
  // ---------------------------------------------------------------------------

  private def methodLevelBFS(
    classByName: Map[String, ClassNode],
    entryPoints: Set[String]
  ): (Set[String], Set[(String, String, String)]) = {
    // Build subclass map for virtual dispatch resolution
    val subclasses = mutable.Map.empty[String, mutable.Set[String]]
    for ((_, cn) <- classByName) {
      if (cn.superName != null)
        subclasses.getOrElseUpdate(cn.superName, mutable.Set.empty) += cn.name
      if (cn.interfaces != null)
        cn.interfaces.asScala.foreach(iface =>
          subclasses.getOrElseUpdate(iface, mutable.Set.empty) += cn.name)
    }

    val reachableClasses = mutable.Set.empty[String]
    val reachableMethods = mutable.Set.empty[(String, String, String)]
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
        classByName.get(owner) match {
          case Some(cn) if cn.methods != null && cn.methods.asScala.exists(m => m.name == name && m.desc == desc) =>
            reachableMethods += key
            methodWorklist += key
            enqueueClass(owner)
          case _ =>
        }
      }
    }

    /** Resolve a method call by walking up the hierarchy from `owner`. */
    def resolveAndEnqueueMethod(owner: String, name: String, desc: String): Unit = {
      classByName.get(owner) match {
        case Some(cn) if cn.methods != null && cn.methods.asScala.exists(m => m.name == name && m.desc == desc) =>
          enqueueMethod(owner, name, desc)
        case Some(cn) =>
          resolveMethodUp(cn, name, desc)
          enqueueClass(owner)
        case None =>
      }
    }

    def resolveMethodUp(cn: ClassNode, name: String, desc: String): Boolean = {
      var found = false
      if (!found && cn.superName != null) found = resolveInClass(cn.superName, name, desc)
      if (!found && cn.interfaces != null) {
        val it = cn.interfaces.iterator()
        while (it.hasNext && !found) found = resolveInClass(it.next(), name, desc)
      }
      found
    }

    def resolveInClass(className: String, name: String, desc: String): Boolean = {
      classByName.get(className) match {
        case Some(cn) =>
          if (cn.methods != null && cn.methods.asScala.exists(m => m.name == name && m.desc == desc)) {
            enqueueMethod(className, name, desc)
            true
          } else resolveMethodUp(cn, name, desc)
        case None => false
      }
    }

    val virtualCallTargets = mutable.Set.empty[(String, String, String)]

    def enqueueVirtualCall(owner: String, name: String, desc: String): Unit = {
      virtualCallTargets += ((owner, name, desc))
      resolveAndEnqueueMethod(owner, name, desc)
      enqueueOverridesInSubclasses(owner, name, desc)
    }

    def enqueueOverridesInSubclasses(owner: String, name: String, desc: String): Unit = {
      for (sub <- subclasses.getOrElse(owner, mutable.Set.empty)) {
        if (reachableClasses.contains(sub))
          enqueueMethod(sub, name, desc)
        enqueueOverridesInSubclasses(sub, name, desc)
      }
    }

    // Collect method signatures inherited from external (non-analyzed) superclasses/interfaces.
    // Methods overriding external methods must be treated as reachable since external code
    // can call them (e.g. JDK calling abstract method implementations via virtual dispatch).
    val externalMethodsCache = mutable.Map.empty[String, Set[(String, String)]]
    def externalMethods(className: String): Set[(String, String)] = {
      externalMethodsCache.getOrElseUpdate(className, {
        classByName.get(className) match {
          case Some(cn) =>
            val fromSuper = if (cn.superName != null) externalMethods(cn.superName) else Set.empty[(String, String)]
            val fromIfaces = if (cn.interfaces != null) cn.interfaces.asScala.flatMap(externalMethods).toSet else Set.empty[(String, String)]
            fromSuper ++ fromIfaces
          case None =>
            collectExternalClassMethods(className)
        }
      })
    }

    // Seed: all methods in entry point classes
    for (ep <- entryPoints; cn <- classByName.get(ep)) {
      enqueueClass(ep)
      if (cn.methods != null)
        cn.methods.asScala.foreach(mn => enqueueMethod(ep, mn.name, mn.desc))
    }

    // Main BFS loop
    while (classWorklist.nonEmpty || methodWorklist.nonEmpty) {
      while (classWorklist.nonEmpty) {
        val className = classWorklist.dequeue()
        for (cn <- classByName.get(className)) {
          if (cn.superName != null) enqueueClass(cn.superName)
          if (cn.interfaces != null) cn.interfaces.asScala.foreach(enqueueClass)

          if (cn.methods != null) {
            cn.methods.asScala.filter(_.name == "<clinit>")
              .foreach(mn => enqueueMethod(className, mn.name, mn.desc))

            // Methods that override external (non-analyzed) class methods are implicitly
            // reachable — external code can invoke them via virtual dispatch.
            val inherited = externalMethods(className)
            if (inherited.nonEmpty) {
              cn.methods.asScala.foreach { mn =>
                if (inherited.contains((mn.name, mn.desc)))
                  enqueueMethod(className, mn.name, mn.desc)
              }
            }
          }

          if (cn.fields != null)
            cn.fields.asScala.foreach(fn => addTypeDescClassRefs(fn.desc, enqueueClass))

          addAnnotationClassRefs(cn.visibleAnnotations, enqueueClass)
          addAnnotationClassRefs(cn.invisibleAnnotations, enqueueClass)

          for ((targetOwner, name, desc) <- virtualCallTargets)
            if (isSubclassOf(className, targetOwner, classByName))
              enqueueMethod(className, name, desc)
        }
      }

      while (methodWorklist.nonEmpty) {
        val (className, methodName, methodDesc) = methodWorklist.dequeue()
        for (cn <- classByName.get(className);
             mn <- cn.methods.asScala.find(m => m.name == methodName && m.desc == methodDesc))
          processMethodRefs(mn, enqueueClass, resolveAndEnqueueMethod, enqueueVirtualCall)
      }
    }

    (reachableClasses.toSet, reachableMethods.toSet)
  }

  // ---------------------------------------------------------------------------
  // Phase 2: Load closure
  // ---------------------------------------------------------------------------

  /**
   * Ensure all classes referenced by execution-reachable classes are also included.
   * These "load-reachable" classes are needed for JVM class loading/verification.
   * For load-reachable classes, we follow their type hierarchy and descriptor types
   * but NOT their method bodies.
   */
  private def loadClosure(
    execReachable: Set[String],
    reachableMethods: Set[(String, String, String)],
    classByName: Map[String, ClassNode],
  ): Set[String] = {
    val allReachable = mutable.Set.empty[String]
    allReachable ++= execReachable

    val worklist = mutable.Queue.empty[String]

    // Seed: collect class references from execution-reachable classes,
    // but only scan methods that are reachable (unreachable methods will be stripped)
    for (name <- execReachable; cn <- classByName.get(name))
      collectAllClassRefs(cn, reachableMethods, allReachable, worklist, classByName)

    // Transitively close: load-reachable classes need their supertypes,
    // field types, and method descriptor types to also be loadable
    while (worklist.nonEmpty) {
      val name = worklist.dequeue()
      for (cn <- classByName.get(name))
        collectLoadDeps(cn, allReachable, worklist, classByName)
    }

    allReachable.toSet
  }

  /** Collect class references from a ClassNode, but only from reachable methods. */
  private def collectAllClassRefs(
    cn: ClassNode,
    reachableMethods: Set[(String, String, String)],
    reachable: mutable.Set[String],
    worklist: mutable.Queue[String],
    classByName: Map[String, ClassNode],
  ): Unit = {
    def enqueue(name: String): Unit = {
      if (!reachable.contains(name) && classByName.contains(name)) {
        reachable += name
        worklist += name
      }
    }

    if (cn.superName != null) enqueue(cn.superName)
    if (cn.interfaces != null) cn.interfaces.asScala.foreach(enqueue)

    if (cn.fields != null)
      cn.fields.asScala.foreach(fn => addTypeDescClassRefs(fn.desc, enqueue))

    if (cn.methods != null) {
      cn.methods.asScala.foreach { mn =>
        // Only scan reachable methods (or abstract/native/bridge which aren't stripped).
        // Unreachable methods will be stripped, so their references don't matter.
        val isAbstract = (mn.access & Opcodes.ACC_ABSTRACT) != 0
        val isNative = (mn.access & Opcodes.ACC_NATIVE) != 0
        val isBridge = (mn.access & Opcodes.ACC_BRIDGE) != 0
        if (isAbstract || isNative || isBridge || reachableMethods.contains((cn.name, mn.name, mn.desc))) {
          addMethodDescClassRefs(mn.desc, enqueue)
          if (mn.exceptions != null) mn.exceptions.asScala.foreach(enqueue)
          collectInstructionClassRefs(mn, enqueue)
          if (mn.tryCatchBlocks != null)
            mn.tryCatchBlocks.asScala.foreach(tcb => if (tcb.`type` != null) enqueue(tcb.`type`))
        }
      }
    }

    addAnnotationClassRefs(cn.visibleAnnotations, enqueue)
    addAnnotationClassRefs(cn.invisibleAnnotations, enqueue)
  }

  /** Collect class references that are needed for a class to be LOADABLE (not executable). */
  private def collectLoadDeps(
    cn: ClassNode,
    reachable: mutable.Set[String],
    worklist: mutable.Queue[String],
    classByName: Map[String, ClassNode],
  ): Unit = {
    def enqueue(name: String): Unit = {
      if (!reachable.contains(name) && classByName.contains(name)) {
        reachable += name
        worklist += name
      }
    }

    // Supertypes
    if (cn.superName != null) enqueue(cn.superName)
    if (cn.interfaces != null) cn.interfaces.asScala.foreach(enqueue)

    // Field types
    if (cn.fields != null)
      cn.fields.asScala.foreach(fn => addTypeDescClassRefs(fn.desc, enqueue))

    // Method descriptor types (parameter + return types for ALL methods)
    if (cn.methods != null)
      cn.methods.asScala.foreach(mn => addMethodDescClassRefs(mn.desc, enqueue))

    // Annotations
    addAnnotationClassRefs(cn.visibleAnnotations, enqueue)
    addAnnotationClassRefs(cn.invisibleAnnotations, enqueue)
  }

  /** Collect class references from instruction nodes. */
  private def collectInstructionClassRefs(mn: MethodNode, enqueue: String => Unit): Unit = {
    if (mn.instructions == null) return
    val iter = mn.instructions.iterator()
    while (iter.hasNext) {
      iter.next() match {
        case mi: MethodInsnNode =>
          enqueue(mi.owner)
          addMethodDescClassRefs(mi.desc, enqueue)
        case fi: FieldInsnNode =>
          enqueue(fi.owner)
          addTypeDescClassRefs(fi.desc, enqueue)
        case ti: TypeInsnNode =>
          addInternalOrArrayClassRef(ti.desc, enqueue)
        case mri: MultiANewArrayInsnNode =>
          addTypeDescClassRefs(mri.desc, enqueue)
        case ldc: LdcInsnNode =>
          ldc.cst match {
            case t: Type =>
              if (t.getSort == Type.OBJECT) enqueue(t.getInternalName)
              else if (t.getSort == Type.ARRAY) addTypeDescClassRefs(t.getDescriptor, enqueue)
              else if (t.getSort == Type.METHOD) addMethodDescClassRefs(t.getDescriptor, enqueue)
            case h: Handle =>
              enqueue(h.getOwner)
              addMethodDescClassRefs(h.getDesc, enqueue)
            case _ =>
          }
        case inv: InvokeDynamicInsnNode =>
          addMethodDescClassRefs(inv.desc, enqueue)
          if (inv.bsm != null) {
            enqueue(inv.bsm.getOwner)
            addMethodDescClassRefs(inv.bsm.getDesc, enqueue)
          }
          if (inv.bsmArgs != null) {
            for (arg <- inv.bsmArgs) arg match {
              case t: Type =>
                if (t.getSort == Type.OBJECT) enqueue(t.getInternalName)
                else if (t.getSort == Type.METHOD) addMethodDescClassRefs(t.getDescriptor, enqueue)
              case h: Handle =>
                enqueue(h.getOwner)
                addMethodDescClassRefs(h.getDesc, enqueue)
              case _ =>
            }
          }
        case _ =>
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def isSubclassOf(child: String, parent: String, classByName: Map[String, ClassNode]): Boolean = {
    if (child == parent) return true
    classByName.get(child) match {
      case Some(cn) =>
        (cn.superName != null && isSubclassOf(cn.superName, parent, classByName)) ||
        (cn.interfaces != null && cn.interfaces.asScala.exists(i => isSubclassOf(i, parent, classByName)))
      case None => false
    }
  }

  private def processMethodRefs(
    mn: MethodNode,
    enqueueClass: String => Unit,
    resolveAndEnqueueMethod: (String, String, String) => Unit,
    enqueueVirtualCall: (String, String, String) => Unit,
  ): Unit = {
    addMethodDescClassRefs(mn.desc, enqueueClass)
    if (mn.exceptions != null) mn.exceptions.asScala.foreach(enqueueClass)

    if (mn.instructions != null) {
      val iter = mn.instructions.iterator()
      while (iter.hasNext) {
        iter.next() match {
          case mi: MethodInsnNode =>
            enqueueClass(mi.owner)
            addMethodDescClassRefs(mi.desc, enqueueClass)
            mi.getOpcode match {
              case Opcodes.INVOKESTATIC | Opcodes.INVOKESPECIAL =>
                resolveAndEnqueueMethod(mi.owner, mi.name, mi.desc)
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
                resolveAndEnqueueMethod(h.getOwner, h.getName, h.getDesc)
              case _ =>
            }
          case inv: InvokeDynamicInsnNode =>
            addMethodDescClassRefs(inv.desc, enqueueClass)
            if (inv.bsm != null) {
              enqueueClass(inv.bsm.getOwner)
              addMethodDescClassRefs(inv.bsm.getDesc, enqueueClass)
              resolveAndEnqueueMethod(inv.bsm.getOwner, inv.bsm.getName, inv.bsm.getDesc)
            }
            if (inv.bsmArgs != null) {
              for (arg <- inv.bsmArgs) arg match {
                case t: Type =>
                  if (t.getSort == Type.OBJECT) enqueueClass(t.getInternalName)
                  else if (t.getSort == Type.METHOD) addMethodDescClassRefs(t.getDescriptor, enqueueClass)
                case h: Handle =>
                  enqueueClass(h.getOwner)
                  addMethodDescClassRefs(h.getDesc, enqueueClass)
                  resolveAndEnqueueMethod(h.getOwner, h.getName, h.getDesc)
                case _ =>
              }
            }
          case _ =>
        }
      }
    }

    if (mn.tryCatchBlocks != null)
      mn.tryCatchBlocks.asScala.foreach(tcb => if (tcb.`type` != null) enqueueClass(tcb.`type`))
  }

  private def addTypeDescClassRefs(desc: String, enqueue: String => Unit): Unit = {
    if (desc == null || desc.isEmpty) return
    var i = 0
    while (i < desc.length) {
      desc.charAt(i) match {
        case 'L' =>
          val end = desc.indexOf(';', i)
          if (end > i) { enqueue(desc.substring(i + 1, end)); i = end + 1 }
          else return
        case '[' => i += 1
        case _ => return
      }
    }
  }

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
    } catch { case _: Exception => }
  }

  private def addTypeFromAsmType(t: Type, enqueue: String => Unit): Unit = {
    if (t.getSort == Type.OBJECT) enqueue(t.getInternalName)
    else if (t.getSort == Type.ARRAY) addTypeFromAsmType(t.getElementType, enqueue)
  }

  private def addInternalOrArrayClassRef(desc: String, enqueue: String => Unit): Unit = {
    if (desc.startsWith("[")) addTypeDescClassRefs(desc, enqueue)
    else enqueue(desc)
  }

  private def addAnnotationClassRefs(annotations: java.util.List[AnnotationNode], enqueue: String => Unit): Unit = {
    if (annotations == null) return
    annotations.asScala.foreach(an => addTypeDescClassRefs(an.desc, enqueue))
  }
}
