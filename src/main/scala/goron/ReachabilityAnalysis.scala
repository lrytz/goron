/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.tools.asm
import scala.tools.asm.tree._
import scala.tools.asm.{Handle, Opcodes, Type}

/** Whole-program reachability analysis in two phases:
  *
  * Phase 1 (method-level BFS): Starting from entry point classes, follows method calls at method granularity. If a
  * class has 100 methods but only 1 is called, only that method's references are followed. This determines the
  * "execution-reachable" set — classes whose code will actually run.
  *
  * Phase 2 (load closure): For each execution-reachable class, ensures all classes referenced anywhere in its classfile
  * (method bodies, descriptors, constant pool) are also present. These "load-reachable" classes are needed for JVM
  * class loading and verification, but their own method bodies are NOT traversed — only their type hierarchy and
  * descriptor types are followed transitively.
  *
  * This two-phase approach eliminates unreferenced classes aggressively while ensuring retained classes can actually be
  * loaded by the JVM.
  */
object ReachabilityAnalysis {

  /** Compute the set of reachable class internal names, starting from the given entry points.
    *
    * @param classNodes
    *   all classes in the program
    * @param entryPoints
    *   internal names of entry point classes (all their methods are roots)
    * @return
    *   set of reachable class internal names
    */
  def reachableClasses(
      hierarchy: ClassHierarchy,
      entryPoints: Set[String],
      progressCallback: String => Unit = _ => ()
  ): Set[String] = {
    val (execReachable, reachableMethods) = methodLevelBFS(hierarchy, entryPoints, progressCallback)
    loadClosure(execReachable, reachableMethods, hierarchy.classByName, willStripMethods = false)
  }

  /** Like `reachableClasses`, but also returns the set of reachable methods and the execution-reachable class set
    * (before load closure).
    *
    * @return
    *   (allReachableClasses, execReachableClasses, reachableMethods)
    */
  def reachableClassesAndMethods(
      hierarchy: ClassHierarchy,
      entryPoints: Set[String],
      progressCallback: String => Unit = _ => ()
  ): (Set[String], Set[String], Set[(String, String, String)]) = {
    val (execReachable, reachableMethods) = methodLevelBFS(hierarchy, entryPoints, progressCallback)
    val allReachable = loadClosure(execReachable, reachableMethods, hierarchy.classByName, willStripMethods = true)
    (allReachable, execReachable, reachableMethods)
  }

  /** Remove unreachable methods from ClassNodes in-place. Only strips from execution-reachable classes (not
    * load-closure-only). Never strips abstract, native, or bridge methods, nor methods that override/implement methods
    * from classes outside the analyzed set (e.g. JDK classes whose method bodies we can't trace).
    */
  def stripUnreachableMethods(
      classNodes: Iterable[ClassNode],
      reachableMethods: Set[(String, String, String)],
      execReachableClasses: Set[String]
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
  private def collectExternalClassMethods(
      internalName: String,
      cache: mutable.Map[String, Set[(String, String)]]
  ): Set[(String, String)] = {
    cache.getOrElseUpdate(
      internalName, {
        try {
          val stream = Thread
            .currentThread()
            .getContextClassLoader
            .getResourceAsStream(internalName + ".class")
          if (stream == null) return Set.empty
          val bytes =
            try stream.readAllBytes()
            finally stream.close()
          val cr = new asm.ClassReader(bytes)
          val methods = mutable.Set.empty[(String, String)]
          val superNames = mutable.ListBuffer.empty[String]
          cr.accept(
            new asm.ClassVisitor(Opcodes.ASM9) {
              override def visit(
                  version: Int,
                  access: Int,
                  name: String,
                  signature: String,
                  superName: String,
                  interfaces: Array[String]
              ): Unit = {
                if (superName != null) superNames += superName
                if (interfaces != null) superNames ++= interfaces
              }
              override def visitMethod(
                  access: Int,
                  name: String,
                  descriptor: String,
                  signature: String,
                  exceptions: Array[String]
              ): asm.MethodVisitor = {
                methods += ((name, descriptor))
                null
              }
            },
            asm.ClassReader.SKIP_CODE | asm.ClassReader.SKIP_DEBUG | asm.ClassReader.SKIP_FRAMES
          )
          methods.toSet ++ superNames.flatMap(collectExternalClassMethods(_, cache))
        } catch {
          case _: Exception => Set.empty
        }
      }
    )
  }

  // ---------------------------------------------------------------------------
  // Phase 1: Method-level BFS
  // ---------------------------------------------------------------------------

  private def methodLevelBFS(
      hierarchy: ClassHierarchy,
      entryPoints: Set[String],
      progressCallback: String => Unit
  ): (Set[String], Set[(String, String, String)]) = {
    val classByName = hierarchy.classByName

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
      if (!reachableMethods.contains(key) && hierarchy.hasMethod(owner, name, desc)) {
        reachableMethods += key
        methodWorklist += key
        enqueueClass(owner)
      }
    }

    /** Resolve a method call by walking up the hierarchy from `owner`. */
    def resolveAndEnqueueMethod(owner: String, name: String, desc: String): Unit = {
      if (hierarchy.hasMethod(owner, name, desc)) {
        enqueueMethod(owner, name, desc)
      } else if (classByName.contains(owner)) {
        resolveMethodUp(owner, name, desc)
        enqueueClass(owner)
      }
    }

    def resolveMethodUp(className: String, name: String, desc: String): Boolean = {
      classByName.get(className) match {
        case Some(cn) =>
          var found = false
          if (cn.superName != null) found = resolveInClass(cn.superName, name, desc)
          if (!found && cn.interfaces != null) {
            val it = cn.interfaces.iterator()
            while (it.hasNext && !found) found = resolveInClass(it.next(), name, desc)
          }
          found
        case None => false
      }
    }

    def resolveInClass(className: String, name: String, desc: String): Boolean = {
      if (hierarchy.hasMethod(className, name, desc)) {
        enqueueMethod(className, name, desc)
        true
      } else resolveMethodUp(className, name, desc)
    }

    // RTA virtual dispatch: index instantiated classes by supertype and virtual calls by owner
    // for O(relevant) lookups instead of O(all) scans.
    val virtualCallsByOwner = mutable.Map.empty[String, mutable.Set[(String, String)]]
    val instantiatedClasses = mutable.Set.empty[String]
    val instantiatedBySuper = mutable.Map.empty[String, mutable.Set[String]]
    var virtualCallCount = 0

    def markInstantiated(className: String): Unit = {
      if (instantiatedClasses.add(className)) {
        enqueueClass(className)
        // Add to supertype index and resolve pending virtual calls
        for (sup <- hierarchy.transitiveSupertypes.getOrElse(className, Set(className))) {
          instantiatedBySuper.getOrElseUpdate(sup, mutable.Set.empty) += className
          for ((name, desc) <- virtualCallsByOwner.getOrElse(sup, Nil))
            resolveAndEnqueueMethod(className, name, desc)
        }
      }
    }

    def enqueueVirtualCall(owner: String, name: String, desc: String): Unit = {
      val isNew = virtualCallsByOwner.getOrElseUpdate(owner, mutable.Set.empty).add((name, desc))
      enqueueClass(owner)
      if (isNew) {
        virtualCallCount += 1
        // The JVM's INVOKEINTERFACE/INVOKEVIRTUAL symbolic resolution requires the method
        // to exist on the declared owner (or its supertypes). Resolve from the owner upward
        // to ensure the method is retained at the resolution target, not just on concrete
        // dispatch targets which may be different classes.
        resolveAndEnqueueMethod(owner, name, desc)
        // Resolve on already-instantiated subtypes
        for (inst <- instantiatedBySuper.getOrElse(owner, Nil))
          resolveAndEnqueueMethod(inst, name, desc)
      }
    }

    // Collect method signatures inherited from external (non-analyzed) superclasses/interfaces.
    // Methods overriding external methods must be treated as reachable since external code
    // can call them (e.g. JDK calling abstract method implementations via virtual dispatch).
    val externalMethodsCache = mutable.Map.empty[String, Set[(String, String)]]
    val externalClassMethodsCache = mutable.Map.empty[String, Set[(String, String)]]
    def externalMethods(className: String): Set[(String, String)] = {
      externalMethodsCache.getOrElseUpdate(
        className, {
          classByName.get(className) match {
            case Some(cn) =>
              val fromSuper = if (cn.superName != null) externalMethods(cn.superName) else Set.empty[(String, String)]
              val fromIfaces =
                if (cn.interfaces != null) cn.interfaces.asScala.flatMap(externalMethods).toSet
                else Set.empty[(String, String)]
              fromSuper ++ fromIfaces
            case None =>
              collectExternalClassMethods(className, externalClassMethodsCache)
          }
        }
      )
    }

    // Seed: all methods in entry point classes
    for (ep <- entryPoints; cn <- classByName.get(ep)) {
      enqueueClass(ep)
      if (cn.methods != null)
        cn.methods.asScala.foreach(mn => enqueueMethod(ep, mn.name, mn.desc))
    }

    // Progress reporting
    var lastProgressTime = System.nanoTime()
    val progressIntervalNs = 5L * 1000000000L // 5 seconds
    var methodsProcessed = 0

    def reportProgress(): Unit = {
      val now = System.nanoTime()
      if (now - lastProgressTime >= progressIntervalNs) {
        progressCallback(
          s"  ${reachableClasses.size} classes, ${reachableMethods.size} methods reachable" +
            s", ${methodsProcessed} methods scanned" +
            s", $virtualCallCount virtual call sites" +
            s", ${instantiatedClasses.size} instantiated types"
        )
        lastProgressTime = now
      }
    }

    // Main BFS loop
    while (classWorklist.nonEmpty || methodWorklist.nonEmpty) {
      while (classWorklist.nonEmpty) {
        val className = classWorklist.dequeue()
        for (cn <- classByName.get(className)) {
          if (cn.superName != null) enqueueClass(cn.superName)
          if (cn.interfaces != null) cn.interfaces.asScala.foreach(enqueueClass)

          if (cn.methods != null) {
            cn.methods.asScala
              .filter(_.name == "<clinit>")
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
        }
      }

      while (methodWorklist.nonEmpty) {
        val (className, methodName, methodDesc) = methodWorklist.dequeue()
        for (mn <- hierarchy.lookupMethod(className, methodName, methodDesc))
          processMethodRefs(mn, enqueueClass, resolveAndEnqueueMethod, enqueueVirtualCall, markInstantiated)
        methodsProcessed += 1
        reportProgress()
      }
    }

    (reachableClasses.toSet, reachableMethods.toSet)
  }

  // ---------------------------------------------------------------------------
  // Phase 2: Load closure
  // ---------------------------------------------------------------------------

  /** Ensure all classes referenced by execution-reachable classes are also included. These "load-reachable" classes are
    * needed for JVM class loading/verification. For load-reachable classes, we follow their type hierarchy and
    * descriptor types but NOT their method bodies.
    */
  /** @param willStripMethods
    *   if true, only methods that survive stripping are scanned; if false, all methods are scanned
    */
  private def loadClosure(
      execReachable: Set[String],
      reachableMethods: Set[(String, String, String)],
      classByName: Map[String, ClassNode],
      willStripMethods: Boolean
  ): Set[String] = {
    val allReachable = mutable.Set.empty[String]
    allReachable ++= execReachable

    val worklist = mutable.Queue.empty[String]

    val retainedMethods = if (willStripMethods) Some(reachableMethods) else None
    for (name <- execReachable; cn <- classByName.get(name))
      collectAllClassRefs(cn, retainedMethods, allReachable, worklist, classByName)

    // Transitively close: load-reachable classes need their supertypes,
    // field types, and method descriptor types to also be loadable
    while (worklist.nonEmpty) {
      val name = worklist.dequeue()
      for (cn <- classByName.get(name))
        collectLoadDeps(cn, allReachable, worklist, classByName)
    }

    allReachable.toSet
  }

  /** Collect class references from a ClassNode needed for JVM class loading and verification. The JVM verifier
    * resolves ALL methods of a class when it is first loaded (not lazily), so types in StackMapTable frames, method
    * descriptors, and exception tables must be present for all methods that survive stripping.
    */
  private def collectAllClassRefs(
      cn: ClassNode,
      retainedMethods: Option[Set[(String, String, String)]],
      reachable: mutable.Set[String],
      worklist: mutable.Queue[String],
      classByName: Map[String, ClassNode]
  ): Unit = {
    def enqueue(name: String): Unit = {
      if (!reachable.contains(name) && classByName.contains(name)) {
        reachable += name
        worklist += name
      }
    }

    collectClassStructureRefs(cn, enqueue, retainedMethods)
  }

  /** Collect class references needed for a class to be LOADABLE. Includes supertypes, method/field descriptors,
    * and all types referenced in method bodies (stack map frames, instructions, exception tables) since the JVM
    * verifier resolves these eagerly at class load time.
    */
  private def collectLoadDeps(
      cn: ClassNode,
      reachable: mutable.Set[String],
      worklist: mutable.Queue[String],
      classByName: Map[String, ClassNode]
  ): Unit = {
    def enqueue(name: String): Unit = {
      if (!reachable.contains(name) && classByName.contains(name)) {
        reachable += name
        worklist += name
      }
    }

    collectClassStructureRefs(cn, enqueue, None)
  }

  /** Collect all class references from a ClassNode's structure: supertypes, field/method descriptors, and all
    * types in method bodies that the JVM verifier may resolve during class loading.
    *
    * @param retainedMethods
    *   if Some, only scan methods that will survive stripping (reachable + abstract/native/bridge). If None, scan
    *   all methods (for load-only classes where no stripping occurs).
    */
  private def collectClassStructureRefs(
      cn: ClassNode,
      enqueue: String => Unit,
      retainedMethods: Option[Set[(String, String, String)]]
  ): Unit = {
    // Supertypes are eagerly resolved during class loading
    if (cn.superName != null) enqueue(cn.superName)
    if (cn.interfaces != null) cn.interfaces.asScala.foreach(enqueue)

    // Field descriptors
    if (cn.fields != null)
      cn.fields.asScala.foreach(fn => addTypeDescClassRefs(fn.desc, enqueue))

    // Scan methods that will be in the output class: the JVM verifier resolves types
    // in StackMapTable frames, method descriptors, exception tables, and instructions
    if (cn.methods != null) {
      cn.methods.asScala.foreach { mn =>
        val shouldScan = retainedMethods match {
          case None => true
          case Some(reachable) =>
            val isAbstract = (mn.access & Opcodes.ACC_ABSTRACT) != 0
            val isNative = (mn.access & Opcodes.ACC_NATIVE) != 0
            val isBridge = (mn.access & Opcodes.ACC_BRIDGE) != 0
            isAbstract || isNative || isBridge || reachable.contains((cn.name, mn.name, mn.desc))
        }
        if (shouldScan) {
          addMethodDescClassRefs(mn.desc, enqueue)

          if (mn.tryCatchBlocks != null)
            mn.tryCatchBlocks.asScala.foreach(tcb => if (tcb.`type` != null) enqueue(tcb.`type`))

          if (mn.instructions != null) {
            val iter = mn.instructions.iterator()
            while (iter.hasNext) {
              iter.next() match {
                case ti: TypeInsnNode =>
                  addInternalOrArrayClassRef(ti.desc, enqueue)
                case mi: MethodInsnNode =>
                  enqueue(mi.owner)
                  addMethodDescClassRefs(mi.desc, enqueue)
                case fi: FieldInsnNode =>
                  enqueue(fi.owner)
                  addTypeDescClassRefs(fi.desc, enqueue)
                case inv: InvokeDynamicInsnNode =>
                  addMethodDescClassRefs(inv.desc, enqueue)
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
                case fn: FrameNode =>
                  // StackMapTable frames: the verifier resolves object types in locals and stack
                  if (fn.local != null)
                    fn.local.asScala.foreach { case s: String => enqueue(s); case _ => }
                  if (fn.stack != null)
                    fn.stack.asScala.foreach { case s: String => enqueue(s); case _ => }
                case _ =>
              }
            }
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def processMethodRefs(
      mn: MethodNode,
      enqueueClass: String => Unit,
      resolveAndEnqueueMethod: (String, String, String) => Unit,
      enqueueVirtualCall: (String, String, String) => Unit,
      markInstantiated: String => Unit
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
            if (ti.getOpcode == Opcodes.NEW)
              markInstantiated(ti.desc)
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
            // LambdaMetafactory creates synthetic classes at runtime that implement the
            // functional interface (the INVOKEDYNAMIC return type). These classes inherit
            // default bridge methods (e.g., JFunction0$mcI$sp.apply()Object bridges to
            // apply$mcI$sp()I). Mark the functional interface as instantiated so virtual
            // call resolution retains those default methods.
            if (inv.bsm != null && inv.bsm.getOwner == "java/lang/invoke/LambdaMetafactory") {
              val retType = Type.getReturnType(inv.desc)
              if (retType.getSort == Type.OBJECT)
                markInstantiated(retType.getInternalName)
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
        case _   => return
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
