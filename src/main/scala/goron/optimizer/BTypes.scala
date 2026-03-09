/*
 * Goron, a link-time optimizer for Scala JVM bytecode
 * Copyright EPFL and Lightbend, Inc. dba Akka
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0)
 */

package goron.optimizer

import goron.optimizer.BTypes.{InlineInfo, InternalName}
import goron.optimizer.BackendReporting._
import goron.optimizer.opt._

import java.{util => ju}
import scala.annotation.tailrec
import scala.collection.SortedMap
import scala.collection.immutable.ArraySeq.unsafeWrapArray
import scala.tools.asm
import scala.tools.asm.{Handle, Opcodes}

/** The BTypes component defines The BType class hierarchy. A BType stores all type information that is required after
  * building the ASM nodes. This includes optimizations, generation of InnerClass attributes and generation of stack map
  * frames.
  *
  * The representation is immutable and independent of the compiler data structures, hence it can be queried by
  * concurrent threads.
  */
class BTypes(
    val compilerSettings: CompilerSettings,
    val classpath: goron.Classpath,
    val backendReporting: BackendReporting.Reporter
) {

  import CoreBTypes._

  /** Every ClassBType is cached on construction and accessible through this method.
    *
    * The cache is used when computing stack map frames. The asm.ClassWriter invokes the method `getCommonSuperClass`.
    * In this method we need to obtain the ClassBType for a given internal name. The method assumes that every class
    * type that appears in the bytecode exists in the map
    */
  // OPT: not returning Option[ClassBType] because the Some allocation shows up as a hotspot
  def cachedClassBType(internalName: InternalName): ClassBType =
    classBTypeCache.get(internalName)

  // Concurrent maps because stack map frames are computed when in the class writer, which
  // might run on multiple classes concurrently.
  // Note usage should be private to this file, except for tests
  val classBTypeCache: ju.concurrent.ConcurrentHashMap[InternalName, ClassBType] =
    new ju.concurrent.ConcurrentHashMap[InternalName, ClassBType]
  object BType {
    val emptyArray = Array[BType]()
    def newArray(n: Int): Array[BType] = if (n == 0) emptyArray else new Array[BType](n)
  }
  sealed abstract class BType {
    override def toString: String = BTypeExporter.btypeToString(this)

    /** @return
      *   The Java descriptor of this type. Examples:
      *   - int: I
      *   - java.lang.String: Ljava/lang/String;
      *   - int[]: [I
      *   - Object m(String s, double d): (Ljava/lang/String;D)Ljava/lang/Object;
      */
    final def descriptor = toString

    /** @return
      *   0 for void, 2 for long and double, 1 otherwise
      */
    final def size: Int = this match {
      case UNIT          => 0
      case LONG | DOUBLE => 2
      case _             => 1
    }

    final def isPrimitive: Boolean = this.isInstanceOf[PrimitiveBType]
    final def isRef: Boolean = this.isInstanceOf[RefBType]
    final def isArray: Boolean = this.isInstanceOf[ArrayBType]
    final def isClass: Boolean = this.isInstanceOf[ClassBType]
    final def isMethod: Boolean = this.isInstanceOf[MethodBType]

    final def isNonVoidPrimitiveType = isPrimitive && this != UNIT

    final def isNullType = this == srNullRef
    final def isNothingType = this == srNothingRef

    final def isBoxed = this.isClass && boxedClasses(this.asClassBType)

    final def isIntSizedType = this == BOOL || this == CHAR || this == BYTE ||
      this == SHORT || this == INT
    final def isIntegralType = this == INT || this == BYTE || this == LONG ||
      this == CHAR || this == SHORT
    final def isRealType = this == FLOAT || this == DOUBLE
    final def isNumericType = isIntegralType || isRealType
    final def isWideType = size == 2

    /*
     * Subtype check `this <:< other` on BTypes that takes into account the JVM built-in numeric
     * promotions (e.g. BYTE to INT). Its operation can be visualized more easily in terms of the
     * Java bytecode type hierarchy.
     */
    final def conformsTo(other: BType): Either[NoClassBTypeInfo, Boolean] = tryEither(Right({
      assert(isRef || isPrimitive, s"conformsTo cannot handle $this")
      assert(other.isRef || other.isPrimitive, s"conformsTo cannot handle $other")

      this match {
        case ArrayBType(component) =>
          if (other == ObjectRef || other == jlCloneableRef || other == jiSerializableRef) true
          else
            other match {
              case ArrayBType(otherComponent) =>
                // Array[Short]().isInstanceOf[Array[Int]] is false
                // but Array[String]().isInstanceOf[Array[Object]] is true
                if (component.isPrimitive || otherComponent.isPrimitive) component == otherComponent
                else component.conformsTo(otherComponent).orThrow
              case _ => false
            }

        case classType: ClassBType =>
          // Quick test for ObjectRef to make a common case fast
          other == ObjectRef || (other match {
            case otherClassType: ClassBType => classType.isSubtypeOf(otherClassType).orThrow
            case _                          => false
          })

        case _ =>
          // there are no bool/byte/short/char primitives at runtime, they are represented as ints.
          // instructions like i2s are used to truncate, the result is again an int. conformsTo
          // returns true for conversions that don't need a truncating instruction. see also emitT2T.
          // note that for primitive arrays, Array[Short]().isInstanceOf[Array[Int]] is false.
          this == other || ((this, other) match {
            case (BOOL, BYTE | SHORT | INT) => true
            case (BYTE, SHORT | INT)        => true
            case (SHORT, INT)               => true
            case (CHAR, INT)                => true
            case _                          => false
          })
      }
    }))

    /** Compute the upper bound of two types. Takes promotions of numeric primitives into account.
      */
    final def maxType(other: BType): BType = this match {
      case pt: PrimitiveBType => pt.maxValueType(other)

      case _: ArrayBType | _: ClassBType =>
        if (isNothingType) return other
        if (other.isNothingType) return this
        if (this == other) return this

        assert(other.isRef, s"Cannot compute maxType: $this, $other")
        // Approximate `lub`. The common type of two references is always ObjectReference.
        ObjectRef

      case _: MethodBType =>
        assertionError(s"unexpected method type when computing maxType: $this")
    }

    /** See documentation of [[typedOpcode]]. The numbers are taken from asm.Type.VOID_TYPE ff., the values are those
      * shifted by << 8.
      */
    private def loadStoreOpcodeOffset: Int = this match {
      case UNIT | INT  => 0
      case BOOL | BYTE => 5
      case CHAR        => 6
      case SHORT       => 7
      case FLOAT       => 2
      case LONG        => 1
      case DOUBLE      => 3
      case _           => 4
    }

    /** See documentation of [[typedOpcode]]. The numbers are taken from asm.Type.VOID_TYPE ff., the values are those
      * shifted by << 16.
      */
    private def typedOpcodeOffset: Int = this match {
      case UNIT                             => 5
      case BOOL | CHAR | BYTE | SHORT | INT => 0
      case FLOAT                            => 2
      case LONG                             => 1
      case DOUBLE                           => 3
      case _                                => 4
    }

    /** Some JVM opcodes have typed variants. This method returns the correct opcode according to the type.
      *
      * @param opcode
      *   A JVM instruction opcode. This opcode must be one of ILOAD, ISTORE, IALOAD, IASTORE, IADD, ISUB, IMUL, IDIV,
      *   IREM, INEG, ISHL, ISHR, IUSHR, IAND, IOR IXOR and IRETURN.
      * @return
      *   The opcode adapted to this java type. For example, if this type is `float` and `opcode` is `IRETURN`, this
      *   method returns `FRETURN`.
      */
    final def typedOpcode(opcode: Int): Int = {
      if (opcode == Opcodes.IALOAD || opcode == Opcodes.IASTORE)
        opcode + loadStoreOpcodeOffset
      else
        opcode + typedOpcodeOffset
    }

    /** The asm.Type corresponding to this BType.
      *
      * Note about asm.Type.getObjectType (*): For class types, the method expects the internal name, i.e. without the
      * surrounding 'L' and ';'. For array types on the other hand, the method expects a full descriptor, for example
      * "[Ljava/lang/String;".
      *
      * See method asm.Type.getType that creates a asm.Type from a type descriptor
      *   - for an OBJECT type, the 'L' and ';' are not part of the range of the created Type
      *   - for an ARRAY type, the full descriptor is part of the range
      */
    def toASMType: asm.Type = this match {
      case p: PrimitiveBType        => p.asmType
      case ClassBType(internalName) => asm.Type.getObjectType(internalName) // see (*) above
      case a: ArrayBType            => asm.Type.getObjectType(a.descriptor)
      case m: MethodBType           => asm.Type.getMethodType(m.descriptor)
    }

    def asRefBType: RefBType = this.asInstanceOf[RefBType]
    def asArrayBType: ArrayBType = this.asInstanceOf[ArrayBType]
    def asClassBType: ClassBType = this.asInstanceOf[ClassBType]
    def asPrimitiveBType: PrimitiveBType = this.asInstanceOf[PrimitiveBType]
  }

  sealed abstract class PrimitiveBType(val desc: Char, val asmType: asm.Type) extends BType {
    override val toString: String = desc.toString // OPT avoid StringBuilder

    /** The upper bound of two primitive types. The `other` type has to be either a primitive type or Nothing.
      *
      * The maxValueType of (Char, Byte) and of (Char, Short) is Int, to encompass the negative values of Byte and
      * Short. See ticket #2087.
      */
    final def maxValueType(other: BType): BType = {

      def uncomparable: Nothing = assertionError(s"Cannot compute maxValueType: $this, $other")

      if (!other.isPrimitive && !other.isNothingType) uncomparable

      if (other.isNothingType) return this
      if (this == other) return this

      this match {
        case BYTE =>
          if (other == CHAR) INT
          else if (other.isNumericType) other
          else uncomparable

        case SHORT =>
          other match {
            case BYTE                        => SHORT
            case CHAR                        => INT
            case INT | LONG | FLOAT | DOUBLE => other
            case _                           => uncomparable
          }

        case CHAR =>
          other match {
            case BYTE | SHORT                => INT
            case INT | LONG | FLOAT | DOUBLE => other
            case _                           => uncomparable
          }

        case INT =>
          other match {
            case BYTE | SHORT | CHAR   => INT
            case LONG | FLOAT | DOUBLE => other
            case _                     => uncomparable
          }

        case LONG =>
          other match {
            case INT | BYTE | LONG | CHAR | SHORT => LONG
            case DOUBLE                           => DOUBLE
            case FLOAT                            => FLOAT
            case _                                => uncomparable
          }

        case FLOAT =>
          if (other == DOUBLE) DOUBLE
          else if (other.isNumericType) FLOAT
          else uncomparable

        case DOUBLE =>
          if (other.isNumericType) DOUBLE
          else uncomparable

        case UNIT | BOOL => uncomparable
      }
    }
  }

  case object UNIT extends PrimitiveBType('V', asm.Type.VOID_TYPE)
  case object BOOL extends PrimitiveBType('Z', asm.Type.BOOLEAN_TYPE)
  case object CHAR extends PrimitiveBType('C', asm.Type.CHAR_TYPE)
  case object BYTE extends PrimitiveBType('B', asm.Type.BYTE_TYPE)
  case object SHORT extends PrimitiveBType('S', asm.Type.SHORT_TYPE)
  case object INT extends PrimitiveBType('I', asm.Type.INT_TYPE)
  case object FLOAT extends PrimitiveBType('F', asm.Type.FLOAT_TYPE)
  case object LONG extends PrimitiveBType('J', asm.Type.LONG_TYPE)
  case object DOUBLE extends PrimitiveBType('D', asm.Type.DOUBLE_TYPE)

  sealed abstract class RefBType extends BType {

    /** The class or array type of this reference type. Used for ANEWARRAY, MULTIANEWARRAY, INSTANCEOF and CHECKCAST
      * instructions. Also used for emitting invokevirtual calls to (a: Array[T]).clone() for any T, see genApply.
      *
      * In contrast to the descriptor, this string does not contain the surrounding 'L' and ';' for class types, for
      * example "java/lang/String". However, for array types, the full descriptor is used, for example
      * "[Ljava/lang/String;".
      *
      * This can be verified for example using javap or ASMifier.
      */
    def classOrArrayType: String = this match {
      case ClassBType(internalName) => internalName
      case a: ArrayBType            => a.descriptor
    }
  }

  /*
   * InnerClasses and EnclosingMethod attributes (EnclosingMethod is displayed as OUTERCLASS in asm).
   *
   * In this summary, "class" means "class or interface".
   *
   * JLS: https://docs.oracle.com/javase/specs/jls/se8/html/index.html
   * JVMS: https://docs.oracle.com/javase/specs/jvms/se8/html/index.html
   *
   * Terminology
   * -----------
   *
   *  - Nested class (JLS 8): class whose declaration occurs within the body of another class
   *
   *  - Top-level class (JLS 8): non-nested class
   *
   *  - Inner class (JLS 8.1.3): nested class that is not (explicitly or implicitly) static
   *
   *  - Member class (JLS 8.5): class directly enclosed in the body of a class (and not, for
   *    example, defined in a method). Member classes cannot be anonymous. May be static.
   *
   *  - Local class (JLS 14.3): nested, non-anonymous class that is not a member of a class
   *    - cannot be static (therefore they are "inner" classes)
   *    - can be defined in a method, a constructor or in an initializer block
   *
   *  - Initializer block (JLS 8.6 / 8.7): block of statements in a java class
   *    - static initializer: executed before constructor body
   *    - instance initializer: executed when class is initialized (instance creation, static
   *      field access, ...)
   *
   *  - A static nested class can be defined as
   *    - a static member class (explicitly static), or
   *    - a member class of an interface (implicitly static)
   *    - local classes are never static, even if they are defined in a static method.
   *
   *   Note: it is NOT the case that all inner classes (non-static) have an outer pointer. Example:
   *     class C { static void foo { class D {} } }
   *   The class D is an inner class (non-static), but javac does not add an outer pointer to it.
   *
   * InnerClass
   * ----------
   *
   * The JVMS 4.7.6 requires an entry for every class mentioned in a CONSTANT_Class_info in the
   * constant pool (CP) that is not a member of a package (JLS 7.1).
   *
   * The JLS 13.1, points 9. / 10. requires: a class must reference (in the CP)
   *  - its immediately enclosing class
   *  - all of its member classes
   *  - all local and anonymous classes that are referenced (or declared) elsewhere (method,
   *    constructor, initializer block, field initializer)
   *
   * In a comment, the 4.7.6 spec says: this implies an entry in the InnerClass attribute for
   *  - All enclosing classes (except the outermost, which is top-level)
   *    - My comment: not sure how this is implied, below (*) a Java counter-example.
   *      In any case, the Java compiler seems to add all enclosing classes, even if they are not
   *      otherwise mentioned in the CP. So we should do the same.
   *  - All nested classes (including anonymous and local, but not transitively)
   *
   * Fields in the InnerClass entries:
   *  - inner class: the (nested) class C we are talking about
   *  - outer class: the class of which C is a member. Has to be null for non-members, i.e. for
   *                 local and anonymous classes. NOTE: this coincides with the presence of an
   *                 EnclosingMethod attribute (see below)
   *  - inner name:  A string with the simple name of the inner class. Null for anonymous classes.
   *  - flags:       access property flags, details in JVMS, table in 4.7.6. Static flag: see
   *                 discussion below.
   *
   *
   * Note 1: when a nested class is present in the InnerClass attribute, all of its enclosing
   * classes have to be present as well (by the rules above). Example:
   *
   *   class Outer { class I1 { class I2 { } } }
   *   class User { Outer.I1.I2 foo() { } }
   *
   * The return type "Outer.I1.I2" puts "Outer$I1$I2" in the CP, therefore the class is added to the
   * InnerClass attribute. For this entry, the "outer class" field will be "Outer$I1". This in turn
   * adds "Outer$I1" to the CP, which requires adding that class to the InnerClass attribute.
   * (For local / anonymous classes this would not be the case, since the "outer class" attribute
   *  would be empty. However, no class (other than the enclosing class) can refer to them, as they
   *  have no name.)
   *
   * In the current implementation of the Scala compiler, when adding a class to the InnerClass
   * attribute, all of its enclosing classes will be added as well. Javac seems to do the same,
   * see (*).
   *
   *
   * Note 2: If a class name is mentioned only in a CONSTANT_Utf8_info, but not in a
   * CONSTANT_Class_info, the JVMS does not require an entry in the InnerClass attribute. However,
   * the Java compiler seems to add such classes anyway. For example, when using an annotation, the
   * annotation class is stored as a CONSTANT_Utf8_info in the CP:
   *
   *   @O.Ann void foo() { }
   *
   * adds "const #13 = Asciz LO$Ann;;" in the constant pool. The "RuntimeInvisibleAnnotations"
   * attribute refers to that constant pool entry. Even though there is no other reference to
   * `O.Ann`, the java compiler adds an entry for that class to the InnerClass attribute (which
   * entails adding a CONSTANT_Class_info for the class).
   *
   *
   *
   * EnclosingMethod
   * ---------------
   *
   * JVMS 4.7.7: the attribute must be present "if and only if it represents a local class
   * or an anonymous class" (i.e. not for member classes).
   *
   * The attribute is misnamed, it should be called "EnclosingClass". It has to be defined for all
   * local and anonymous classes, no matter if there is an enclosing method or not. Accordingly, the
   * "class" field (see below) must be always defined, while the "method" field may be null.
   *
   * NOTE: When an EnclosingMethod attribute is required (local and anonymous classes), the "outer"
   * field in the InnerClass table must be null.
   *
   * Fields:
   *  - class:  the enclosing class
   *  - method: the enclosing method (or constructor). Null if the class is not enclosed by a
   *            method, i.e. for
   *             - local or anonymous classes defined in (static or non-static) initializer blocks
   *             - anonymous classes defined in initializer blocks or field initializers
   *
   *            Note: the field is required for anonymous classes defined within local variable
   *            initializers (within a method), Java example below (**).
   *
   *            For local and anonymous classes in initializer blocks or field initializers, and
   *            class-level anonymous classes, the scala compiler sets the "method" field to null.
   *
   *
   * (*)
   *   public class Test {
   *     void foo() {
   *       class Foo1 {
   *         // constructor statement block
   *         {
   *           class Foo2 {
   *             class Foo3 { }
   *           }
   *         }
   *       }
   *     }
   *   }
   *
   * The class file Test$1Foo1$1Foo2$Foo3 has no reference to the class Test$1Foo1, however it
   * still contains an InnerClass attribute for Test$1Foo1.
   * Maybe this is just because the Java compiler follows the JVMS comment ("InnerClasses
   * information for each enclosing class").
   *
   *
   * (**)
   *   void foo() {
   *     // anonymous class defined in local variable initializer expression.
   *     Runnable x = true ? (new Runnable() {
   *       public void run() { return; }
   *     }) : null;
   *   }
   *
   * The EnclosingMethod attribute of the anonymous class mentions "foo" in the "method" field.
   *
   *
   * Java Compatibility
   * ------------------
   *
   * In the InnerClass entry for classes in top-level modules, the "outer class" is emitted as the
   * mirror class (or the existing companion class), i.e. C1 is nested in T (not T$).
   * For classes nested in a nested object, the "outer class" is the module class: C2 is nested in T$N$
   * object T {
   *   class C1
   *   object N { class C2 }
   * }
   *
   * Reason: java compat. It's a "best effort" "solution". If you want to use "C1" from Java, you
   * can write "T.C1", and the Java compiler will translate that to the classfile T$C1.
   *
   * If we would emit the "outer class" of C1 as "T$", then in Java you'd need to write "T$.C1"
   * because the java compiler looks at the InnerClass attribute to find if an inner class exists.
   * However, the Java compiler would then translate the '.' to '$' and you'd get the class name
   * "T$$C1". This class file obviously does not exist.
   *
   * Directly using the encoded class name "T$C1" in Java does not work: since the classfile
   * describes a nested class, the Java compiler hides it from the classpath and will report
   * "cannot find symbol T$C1". This means that the class T.N.C2 cannot be referenced from a
   * Java source file in any way.
   *
   *
   * STATIC flag
   * -----------
   *
   * Java: static member classes have the static flag in the InnerClass attribute, for example B in
   *   class A { static class B { } }
   *
   * The spec is not very clear about when the static flag should be emitted. It says: "Marked or
   * implicitly static in source."
   *
   * The presence of the static flag does NOT coincide with the absence of an "outer" field in the
   * class. The java compiler never puts the static flag for local classes, even if they don't have
   * an outer pointer:
   *
   *   class A {
   *     void f()        { class B {} }
   *     static void g() { class C {} }
   *   }
   *
   * B has an outer pointer, C doesn't. Both B and C are NOT marked static in the InnerClass table.
   *
   * It seems sane to follow the same principle in the Scala compiler. So:
   *
   *   package p
   *   object O1 {
   *     class C1 // static inner class
   *     object O2 { // static inner module
   *       def f = {
   *         class C2 { // non-static inner class, even though there's no outer pointer
   *           class C3 // non-static, has an outer pointer
   *         }
   *       }
   *     }
   *   }
   *
   *
   * Specialized Classes, Delambdafy:method closure classes
   * ------------------------------------------------------
   *
   * Specialized classes are always considered top-level, as the InnerClass / EnclosingMethod
   * attributes describe a source-level properties.
   *
   * The same is true for delambdafy:method closure classes. These classes are generated at
   * top-level in the delambdafy phase, no special support is required in the backend.
   *
   * See also BCodeHelpers.considerAsTopLevelImplementationArtifact.
   *
   *
   * Mirror Classes
   * --------------
   *
   * TODO: innerclass attributes on mirror class, bean info class
   */

  /** A ClassBType represents a class or interface type. The necessary information to build a ClassBType is extracted
    * from classfiles via BTypesFromClassfile.
    *
    * The `info` field contains either the class information or an error message why the info could not be computed
    * (e.g. if the class could not be found on the classpath).
    */
  sealed abstract class ClassBType protected (val internalName: InternalName) extends RefBType {

    /** Write-once variable allows initializing a cyclic graph of infos. This is required for nested classes. Example:
      * for the definition `class A { class B }` we have
      *
      * B.info.nestedInfo.outerClass == A A.info.nestedClasses contains B
      */
    // volatile is required to ensure no early initialisation in apply
    // like classic double checked lock in java
    @volatile private var _info: Either[NoClassBTypeInfo, ClassInfo] = null

    def info: Either[NoClassBTypeInfo, ClassInfo] = {
      if (_info eq null)
        // synchronization required to ensure the apply is finished
        // which populates info. ClassBType does not escape apart from via the map
        // and the object mutex is locked prior to insertion. See apply
        this.synchronized {}
      assert(_info != null, s"ClassBType.info not yet assigned: $this")
      _info
    }

    private def checkInfoConsistency(): Unit = {
      if (info.isLeft) return

      // we assert some properties. however, some of the linked ClassBType (members, superClass,
      // interfaces) may not yet have an `_info` (initialization of cyclic structures). so we do a
      // best-effort verification. also we don't report an error if the info is a Left.
      def ifInit(c: ClassBType)(p: ClassBType => Boolean): Boolean = c._info == null || c.info.isLeft || p(c)

      def isJLO(t: ClassBType) = t.internalName == ObjectRef.internalName

      assert(!ClassBType.isInternalPhantomType(internalName), s"Cannot create ClassBType for phantom type $this")

      assert(
        if (info.get.superClass.isEmpty) {
          isJLO(this)
        } else if (isInterface.get) isJLO(info.get.superClass.get)
        else !isJLO(this) && ifInit(info.get.superClass.get)(!_.isInterface.get),
        s"Invalid superClass in $this: ${info.get.superClass}"
      )
      assert(
        info.get.interfaces.forall(c => ifInit(c)(_.isInterface.get)),
        s"Invalid interfaces in $this: ${info.get.interfaces}"
      )

      info.get.nestedClasses.onForce { cs =>
        assert(cs.forall(c => ifInit(c)(_.isNestedClass.get)), cs)
      }
    }

    def isInterface: Either[NoClassBTypeInfo, Boolean] = info.map(i => (i.flags & asm.Opcodes.ACC_INTERFACE) != 0)

    /** The super class chain of this type, starting with Object, ending with `this`. */
    def superClassesChain: Either[NoClassBTypeInfo, List[ClassBType]] = try {
      var res = List(this)
      var sc = info.orThrow.superClass
      while (sc.nonEmpty) {
        res ::= sc.get
        sc = sc.get.info.orThrow.superClass
      }
      Right(res)
    } catch {
      case Invalid(noInfo: NoClassBTypeInfo) => Left(noInfo)
    }

    /** The prefix of the internal name until the last '/', or the empty string.
      */
    def packageInternalName: String = {
      val name = internalName
      name.lastIndexOf('/') match {
        case -1 => ""
        case i  => name.substring(0, i)
      }
    }

    def isPublic: Either[NoClassBTypeInfo, Boolean] = info.map(i => (i.flags & asm.Opcodes.ACC_PUBLIC) != 0)

    def isNestedClass: Either[NoClassBTypeInfo, Boolean] = info.map(_.nestedInfo.force.isDefined)

    def enclosingNestedClassesChain: Either[NoClassBTypeInfo, List[ClassBType]] = {
      isNestedClass.flatMap(isNested => {
        // if isNested is true, we know that info.get is defined, and nestedInfo.get is also defined.
        if (isNested) info.get.nestedInfo.force.get.enclosingClass.enclosingNestedClassesChain.map(this :: _)
        else Right(Nil)
      })
    }

    def innerClassAttributeEntry: Either[NoClassBTypeInfo, Option[InnerClassEntry]] =
      for (i <- info) yield i.nestedInfo.force.map {
        case NestedInfo(_, outerName, innerName, isStaticNestedClass, enteringTyperPrivate) =>
          var flags = i.flags
          if (enteringTyperPrivate)
            flags = (flags & ~Opcodes.ACC_PUBLIC) | Opcodes.ACC_PRIVATE
          // for use of ACC_STATIC in InnerClasses, see `InnerClasses and EnclosingMethod attributes` above
          if (isStaticNestedClass)
            flags |= Opcodes.ACC_STATIC
          else
            flags &= ~Opcodes.ACC_STATIC
          // Kotlin interop (scala/bug#13110)
          if ((flags & Opcodes.ACC_ABSTRACT) != 0)
            flags &= ~Opcodes.ACC_FINAL
          // BCodeHelpers.INNER_CLASSES_FLAGS = ACC_PUBLIC | ACC_PRIVATE | ACC_PROTECTED | ACC_STATIC | ACC_FINAL | ACC_INTERFACE | ACC_ABSTRACT | ACC_SYNTHETIC | ACC_ANNOTATION | ACC_ENUM
          flags &= (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM)

          InnerClassEntry(
            name = internalName,
            outerName = outerName.orNull,
            innerName = innerName.orNull,
            flags = flags
          )
      }

    def inlineInfoAttribute: Either[NoClassBTypeInfo, InlineInfoAttribute] = info.map(i => {
      // InlineInfos serialized for classes should not have warnings.
      assert(i.inlineInfo.warning.isEmpty, i.inlineInfo.warning)
      InlineInfoAttribute(i.inlineInfo)
    })

    def isSubtypeOf(other: ClassBType): Either[NoClassBTypeInfo, Boolean] = try {
      if (this == other) return Right(true)
      if (isInterface.orThrow) {
        if (other == ObjectRef) return Right(true) // interfaces conform to Object
        if (!other.isInterface.orThrow)
          return Right(
            false
          ) // this is an interface, the other is some class other than object. interfaces cannot extend classes, so the result is false.
        // else: this and other are both interfaces. continue to (*)
      } else {
        val sc = info.orThrow.superClass
        if (sc.isDefined && sc.get.isSubtypeOf(other).orThrow)
          return Right(true) // the superclass of this class conforms to other
        if (!other.isInterface.orThrow)
          return Right(false) // this and other are both classes, and the superclass of this does not conform
        // else: this is a class, the other is an interface. continue to (*)
      }

      // (*) check if some interface of this class conforms to other.
      Right(info.orThrow.interfaces.exists(_.isSubtypeOf(other).orThrow))
    } catch {
      case Invalid(noInfo: NoClassBTypeInfo) => Left(noInfo)
    }

    /** Finding the least upper bound in agreement with the bytecode verifier Background:
      * https://xavierleroy.org/publi/bytecode-verification-JAR.pdf
      * http://comments.gmane.org/gmane.comp.java.vm.languages/2293
      * https://github.com/scala/bug/issues/3872#issuecomment-292386375
      */
    def jvmWiseLUB(other: ClassBType): Either[NoClassBTypeInfo, ClassBType] = {
      def isNotNullOrNothing(c: ClassBType) = !c.isNullType && !c.isNothingType
      assert(isNotNullOrNothing(this) && isNotNullOrNothing(other), s"jvmWiseLUB for null or nothing: $this - $other")

      tryEither {
        val res: ClassBType = (this.isInterface.orThrow, other.isInterface.orThrow) match {
          case (true, true) =>
            // exercised by test/files/run/t4761.scala
            if (other.isSubtypeOf(this).orThrow) this
            else if (this.isSubtypeOf(other).orThrow) other
            else ObjectRef

          case (true, false) =>
            if (other.isSubtypeOf(this).orThrow) this else ObjectRef

          case (false, true) =>
            if (this.isSubtypeOf(other).orThrow) other else ObjectRef

          case _ =>
            firstCommonSuffix(superClassesChain.orThrow, other.superClassesChain.orThrow)
        }

        assert(isNotNullOrNothing(res), s"jvmWiseLUB computed: $res")
        Right(res)
      }
    }

    private def firstCommonSuffix(as: List[ClassBType], bs: List[ClassBType]): ClassBType = {
      // assert(as.head == ObjectRef, as.head)
      // assert(bs.head == ObjectRef, bs.head)
      var chainA = as.tail
      var chainB = bs.tail
      var fcs = ObjectRef
      while (chainA.nonEmpty && chainB.nonEmpty && chainA.head == chainB.head) {
        fcs = chainA.head
        chainA = chainA.tail
        chainB = chainB.tail
      }
      fcs
    }

    override val toASMType: asm.Type = super.toASMType
    private[this] var cachedToString: String = null
    override def toString: String = {
      val cached = cachedToString
      if (cached == null) {
        val computed = super.toString
        cachedToString = computed
        computed
      } else cached
    }
  }

  object ClassBType {
    // Primitive classes have no super class. A ClassBType for those is only created when
    // they are actually being compiled (e.g., when compiling scala/Boolean.scala).
    private val hasNoSuper = Set(
      "scala/Unit",
      "scala/Boolean",
      "scala/Char",
      "scala/Byte",
      "scala/Short",
      "scala/Int",
      "scala/Float",
      "scala/Long",
      "scala/Double"
    )

    private val isInternalPhantomType = Set(
      "scala/Null",
      "scala/Nothing"
    )
    def unapply(cr: ClassBType): Some[InternalName] = Some(cr.internalName)

    /** Retrieve the `ClassBType` for the class with the given internal name, creating the entry if it doesn't already
      * exist.
      *
      * @param internalName
      *   The name of the class
      * @param t
      *   A value that will be passed to the `init` function.
      * @param init
      *   Function to initialize the info of this `BType`. During execution of this function, code _may_ reenter into
      *   `apply(internalName, ...)` and retrieve the initializing `ClassBType`.
      */
    final def apply[T](internalName: InternalName, t: T)(
        init: (ClassBType, T) => Either[NoClassBTypeInfo, ClassInfo]
    ): ClassBType = {
      val cached = classBTypeCache.get(internalName)
      if (cached ne null) cached
      else {
        val newRes: ClassBType = new ClassBTypeImpl(internalName)
        // synchronized is required to ensure proper initialisation of info.
        // see comment on def info
        newRes.synchronized {
          classBTypeCache.putIfAbsent(internalName, newRes) match {
            case null =>
              newRes._info = init(newRes, t)
              newRes.checkInfoConsistency()
              newRes
            case old =>
              old
          }
        }
      }
    }
  }
  private final class ClassBTypeImpl(internalName: InternalName) extends ClassBType(internalName)

  /** The type info for a class. Used for symboltable-independent subtype checks in the backend.
    *
    * @param superClass
    *   The super class, not defined for class java/lang/Object.
    * @param interfaces
    *   All transitively implemented interfaces, except for those inherited through the superclass.
    * @param flags
    *   The java flags, obtained through `javaFlags`. Used also to derive the flags for InnerClass entries.
    * @param nestedClasses
    *   Classes nested in this class. Those need to be added to the InnerClass table, see the InnerClass spec summary
    *   above.
    * @param nestedInfo
    *   If this describes a nested class, information for the InnerClass table.
    * @param inlineInfo
    *   Information about this class for the inliner.
    */
  final case class ClassInfo(
      superClass: Option[ClassBType],
      interfaces: List[ClassBType],
      flags: Int,
      nestedClasses: Lazy[List[ClassBType]],
      nestedInfo: Lazy[Option[NestedInfo]],
      inlineInfo: InlineInfo
  )

  /** Information required to add a class to an InnerClass table. The spec summary above explains what information is
    * required for the InnerClass entry.
    *
    * @param enclosingClass
    *   The enclosing class, if it is also nested. When adding a class to the InnerClass table, enclosing nested classes
    *   are also added.
    * @param outerName
    *   The outerName field in the InnerClass entry, may be None.
    * @param innerName
    *   The innerName field, may be None.
    * @param isStaticNestedClass
    *   True if this is a static nested class (not inner class) (*)
    *
    * (*) Note that the STATIC flag in ClassInfo.flags, obtained through javaFlags(classSym), is not correct for the
    * InnerClass entry, see javaFlags. The static flag in the InnerClass describes a source-level property: if the class
    * is in a static context (does not have an outer pointer). This is checked when building the NestedInfo.
    */
  final case class NestedInfo(
      enclosingClass: ClassBType,
      outerName: Option[String],
      innerName: Option[String],
      isStaticNestedClass: Boolean,
      enteringTyperPrivate: Boolean
  )

  /** This class holds the data for an entry in the InnerClass table. See the InnerClass summary above in this file.
    *
    * There's some overlap with the class NestedInfo, but it's not exactly the same and cleaner to keep separate.
    * @param name
    *   The internal name of the class.
    * @param outerName
    *   The internal name of the outer class, may be null.
    * @param innerName
    *   The simple name of the inner class, may be null.
    * @param flags
    *   The flags for this class in the InnerClass entry.
    */
  final case class InnerClassEntry(name: String, outerName: String, innerName: String, flags: Int)

  final case class ArrayBType(componentType: BType) extends RefBType {
    def dimension: Int = componentType match {
      case a: ArrayBType => 1 + a.dimension
      case _             => 1
    }

    @tailrec
    def elementType: BType = componentType match {
      case a: ArrayBType => a.elementType
      case t             => t
    }
  }

  final case class MethodBType(argumentTypes: Array[BType], returnType: BType) extends BType

  object BTypeExporter extends AutoCloseable {
    private[this] val builderTL: ThreadLocal[StringBuilder] = new ThreadLocal[StringBuilder]() {
      override protected def initialValue: StringBuilder = new StringBuilder(64)
    }

    final def btypeToString(btype: BType): String = {
      val builder = builderTL.get()
      builder.setLength(0)
      appendBType(builder, btype)
      builder.toString
    }

    final def appendBType(builder: StringBuilder, btype: BType): Unit = btype match {
      case p: PrimitiveBType        => builder.append(p.desc)
      case ClassBType(internalName) => builder.append('L').append(internalName).append(';')
      case ArrayBType(component)    => builder.append('['); appendBType(builder, component)
      case MethodBType(args, res)   =>
        builder.append('(')
        args.foreach(appendBType(builder, _))
        builder.append(')')
        appendBType(builder, res)
    }
    def close(): Unit = {
      // This will eagerly remove the thread local from the calling thread's ThreadLocalMap. It won't
      // do the same for other threads used by `-Ybackend-parallelism=N`, but in practice this doesn't
      // matter as that thread pool is shutdown at the end of compilation.
      builderTL.remove()
    }
  }

  /* Some definitions that are required for the implementation of BTypes. They are abstract because
   * initializing them requires information from types / symbols, which is not accessible here in
   * BTypes.
   *
   * They are defs (not vals) because they are implemented using vars (see comment on CoreBTypes).
   */

  /** Just a named pair, used in CoreBTypes.srBoxesRuntimeBoxToMethods/srBoxesRuntimeUnboxToMethods.
    */
  final case class MethodNameAndType(name: String, methodType: MethodBType)

  /** Initializes CoreBTypes from well-known internal class names, resolved from the classpath.
    */
  object CoreBTypes {
    // Helper: create a ClassBType by internal name.
    // Creates a cache entry that will have its info populated when first accessed
    // via BTypesFromClassfile.classBTypeFromParsedClassfile (which checks the cache first,
    // then only creates a new entry if one doesn't exist yet).
    // We create entries with a placeholder Left info that will be replaced.
    private def classBType(internalName: String): ClassBType = {
      // We rely on BTypesFromClassfile.classBTypeFromParsedClassfile being called
      // for each core type before its info is needed. This happens because the optimizer
      // loads classes from the bytecode repository, which calls classBTypeFromParsedClassfile.
      // For core types like java/lang/Object, they will be loaded early.
      //
      // The init function resolves from the classpath directly.
      ClassBType(internalName, (classpath, internalName)) { case (res, (cp, iname)) =>
        cp.findClassBytes(iname) match {
          case None =>
            Left(
              BackendReporting.NoClassBTypeInfoMissingBytecode(
                BackendReporting.ClassNotFound(iname, definedInJavaSource = false)
              )
            )
          case Some(bytes) =>
            val classNode = new goron.optimizer.ClassNode1()
            new scala.tools.asm.ClassReader(bytes).accept(classNode, scala.tools.asm.ClassReader.SKIP_FRAMES)
            computeClassInfoFromClassNode(classNode, res)
        }
      }
    }

    /** Compute ClassInfo from a parsed ClassNode. Minimal version for core types. */
    private def computeClassInfoFromClassNode(
        classNode: scala.tools.asm.tree.ClassNode,
        classBType: ClassBType
    ): Either[BackendReporting.NoClassBTypeInfo, ClassInfo] = {
      import scala.jdk.CollectionConverters._
      val superClass = classNode.superName match {
        case null      => None
        case superName => Some(this.classBType(superName))
      }
      val interfaces = classNode.interfaces.asScala.iterator.map(this.classBType).toList
      val flags = classNode.access
      // For core types, we don't need nested class info or inline info
      Right(
        ClassInfo(superClass, interfaces, flags, Lazy(Nil), Lazy(None), BTypes.EmptyInlineInfo)
      )
    }

    // Well-known class types
    lazy val srNothingRef: ClassBType = classBType("scala/runtime/Nothing$")
    lazy val srNullRef: ClassBType = classBType("scala/runtime/Null$")
    lazy val ObjectRef: ClassBType = classBType("java/lang/Object")
    lazy val StringRef: ClassBType = classBType("java/lang/String")
    lazy val PredefRef: ClassBType = classBType("scala/Predef$")
    lazy val jlCloneableRef: ClassBType = classBType("java/lang/Cloneable")
    lazy val jiSerializableRef: ClassBType = classBType("java/io/Serializable")
    lazy val jlIllegalArgExceptionRef: ClassBType = classBType("java/lang/IllegalArgumentException")
    lazy val juHashMapRef: ClassBType = classBType("java/util/HashMap")
    lazy val juMapRef: ClassBType = classBType("java/util/Map")
    lazy val jliCallSiteRef: ClassBType = classBType("java/lang/invoke/CallSite")
    lazy val jliLambdaMetafactoryRef: ClassBType = classBType("java/lang/invoke/LambdaMetafactory")
    lazy val jliMethodTypeRef: ClassBType = classBType("java/lang/invoke/MethodType")
    lazy val jliSerializedLambdaRef: ClassBType = classBType("java/lang/invoke/SerializedLambda")
    lazy val jliMethodHandleRef: ClassBType = classBType("java/lang/invoke/MethodHandle")
    lazy val jliMethodHandlesLookupRef: ClassBType = classBType("java/lang/invoke/MethodHandles$Lookup")
    lazy val srBoxesRunTimeRef: ClassBType = classBType("scala/runtime/BoxesRunTime")
    lazy val srBoxedUnitRef: ClassBType = classBType("scala/runtime/BoxedUnit")
    lazy val srLambdaDeserialize: ClassBType = classBType("scala/runtime/LambdaDeserialize")

    // Boxed types
    private lazy val boxedVoid = classBType("java/lang/Void")
    private lazy val boxedBoolean = classBType("java/lang/Boolean")
    private lazy val boxedByte = classBType("java/lang/Byte")
    private lazy val boxedShort = classBType("java/lang/Short")
    private lazy val boxedCharacter = classBType("java/lang/Character")
    private lazy val boxedInteger = classBType("java/lang/Integer")
    private lazy val boxedLong = classBType("java/lang/Long")
    private lazy val boxedFloat = classBType("java/lang/Float")
    private lazy val boxedDouble = classBType("java/lang/Double")

    lazy val boxedClassOfPrimitive: Map[PrimitiveBType, ClassBType] = Map(
      UNIT -> boxedVoid,
      BOOL -> boxedBoolean,
      BYTE -> boxedByte,
      SHORT -> boxedShort,
      CHAR -> boxedCharacter,
      INT -> boxedInteger,
      LONG -> boxedLong,
      FLOAT -> boxedFloat,
      DOUBLE -> boxedDouble
    )

    lazy val boxedClasses: Set[ClassBType] = boxedClassOfPrimitive.values.toSet

    // Boxing/unboxing method descriptors (hardcoded from known JDK/Scala signatures)
    private lazy val primitiveInfo: List[(String, PrimitiveBType, String, String)] = List(
      // (primName, primBType, boxedInternalName, unboxMethodName)
      ("Boolean", BOOL, "java/lang/Boolean", "booleanValue"),
      ("Byte", BYTE, "java/lang/Byte", "byteValue"),
      ("Short", SHORT, "java/lang/Short", "shortValue"),
      ("Char", CHAR, "java/lang/Character", "charValue"),
      ("Int", INT, "java/lang/Integer", "intValue"),
      ("Long", LONG, "java/lang/Long", "longValue"),
      ("Float", FLOAT, "java/lang/Float", "floatValue"),
      ("Double", DOUBLE, "java/lang/Double", "doubleValue")
    )

    // Z -> MethodNameAndType(boxToBoolean,(Z)Ljava/lang/Boolean;)
    lazy val srBoxesRuntimeBoxToMethods: Map[BType, MethodNameAndType] = primitiveInfo.map {
      case (name, prim, boxed, _) =>
        val desc = MethodBType(Array(prim), classBType(boxed))
        prim -> MethodNameAndType("boxTo" + name, desc)
    }.toMap

    // Z -> MethodNameAndType(unboxToBoolean,(Ljava/lang/Object;)Z)
    lazy val srBoxesRuntimeUnboxToMethods: Map[BType, MethodNameAndType] = primitiveInfo.map {
      case (name, prim, _, _) =>
        val desc = MethodBType(Array(ObjectRef), prim)
        prim -> MethodNameAndType("unboxTo" + name, desc)
    }.toMap

    // java/lang/Boolean -> MethodNameAndType(valueOf,(Z)Ljava/lang/Boolean;)
    lazy val javaBoxMethods: Map[InternalName, MethodNameAndType] = primitiveInfo.map { case (_, prim, boxed, _) =>
      val desc = MethodBType(Array(prim), classBType(boxed))
      boxed -> MethodNameAndType("valueOf", desc)
    }.toMap

    // java/lang/Boolean -> MethodNameAndType(booleanValue,()Z)
    lazy val javaUnboxMethods: Map[InternalName, MethodNameAndType] = primitiveInfo.map {
      case (_, prim, boxed, unboxMethod) =>
        val desc = MethodBType(Array.empty[BType], prim)
        boxed -> MethodNameAndType(unboxMethod, desc)
    }.toMap

    // boolean2Boolean -> (Z)Ljava/lang/Boolean;
    lazy val predefAutoBoxMethods: Map[String, MethodBType] = primitiveInfo.map { case (name, prim, boxed, _) =>
      val methodName = name.toLowerCase + "2" + name
      methodName -> MethodBType(Array(prim), classBType(boxed))
    }.toMap

    // Boolean2boolean -> (Ljava/lang/Boolean;)Z
    lazy val predefAutoUnboxMethods: Map[String, MethodBType] = primitiveInfo.map { case (name, prim, boxed, _) =>
      val methodName = name + "2" + name.toLowerCase
      methodName -> MethodBType(Array(classBType(boxed)), prim)
    }.toMap

    // Ref class methods
    private lazy val refClassesProper: List[(String, BType)] = List(
      ("scala/runtime/BooleanRef", BOOL),
      ("scala/runtime/ByteRef", BYTE),
      ("scala/runtime/ShortRef", SHORT),
      ("scala/runtime/CharRef", CHAR),
      ("scala/runtime/IntRef", INT),
      ("scala/runtime/LongRef", LONG),
      ("scala/runtime/FloatRef", FLOAT),
      ("scala/runtime/DoubleRef", DOUBLE),
      ("scala/runtime/ObjectRef", ObjectRef)
    )

    lazy val srRefCreateMethods: Map[InternalName, MethodNameAndType] = refClassesProper.map {
      case (refClass, elemType) =>
        val desc = MethodBType(Array(elemType), classBType(refClass))
        refClass -> MethodNameAndType("create", desc)
    }.toMap

    lazy val srRefZeroMethods: Map[InternalName, MethodNameAndType] = refClassesProper.map { case (refClass, _) =>
      val desc = MethodBType(Array.empty[BType], classBType(refClass))
      refClass -> MethodNameAndType("zero", desc)
    }.toMap

    lazy val primitiveBoxConstructors: Map[InternalName, MethodNameAndType] = primitiveInfo.map {
      case (_, prim, boxed, _) =>
        val desc = MethodBType(Array(prim), UNIT)
        boxed -> MethodNameAndType("<init>", desc)
    }.toMap

    lazy val srRefConstructors: Map[InternalName, MethodNameAndType] = refClassesProper.map {
      case (refClass, elemType) =>
        val desc = MethodBType(Array(elemType), UNIT)
        refClass -> MethodNameAndType("<init>", desc)
    }.toMap

    lazy val tupleClassConstructors: Map[InternalName, MethodNameAndType] = {
      // Generate constructors for Tuple1 through Tuple22
      (1 to 22).map { arity =>
        val internalName = s"scala/Tuple$arity"
        val params = Array.fill[BType](arity)(ObjectRef)
        val desc = MethodBType(params, UNIT)
        internalName -> MethodNameAndType("<init>", desc)
      }.toMap
    }

    lazy val lambdaMetaFactoryMetafactoryHandle: Handle = new Handle(
      Opcodes.H_INVOKESTATIC,
      jliLambdaMetafactoryRef.internalName,
      "metafactory",
      MethodBType(
        Array(
          jliMethodHandlesLookupRef,
          StringRef,
          jliMethodTypeRef,
          jliMethodTypeRef,
          jliMethodHandleRef,
          jliMethodTypeRef
        ),
        jliCallSiteRef
      ).descriptor,
      /* itf = */ false
    )

    lazy val lambdaMetaFactoryAltMetafactoryHandle: Handle = new Handle(
      Opcodes.H_INVOKESTATIC,
      jliLambdaMetafactoryRef.internalName,
      "altMetafactory",
      MethodBType(
        Array(jliMethodHandlesLookupRef, StringRef, jliMethodTypeRef, ArrayBType(ObjectRef)),
        jliCallSiteRef
      ).descriptor,
      /* itf = */ false
    )

    lazy val lambdaDeserializeBootstrapHandle: Handle = new Handle(
      Opcodes.H_INVOKESTATIC,
      srLambdaDeserialize.internalName,
      "bootstrap",
      MethodBType(
        Array(jliMethodHandlesLookupRef, StringRef, jliMethodTypeRef, ArrayBType(jliMethodHandleRef)),
        jliCallSiteRef
      ).descriptor,
      /* itf = */ false
    )
  }

  /** A deferred value that supports `onForce` callbacks. Used for `nestedClasses` and `nestedInfo` in `ClassInfo` to
    * break initialization cycles (class A's nested info references class B and vice versa).
    */
  sealed abstract class Lazy[+T] {
    def force: T
    def onForce(f: T => Unit): Unit
  }

  object Lazy {
    def apply[T <: AnyRef](t: => T): Lazy[T] = new Deferred[T](() => t)

    private final class Deferred[T <: AnyRef](private var t: () => T) extends Lazy[T] {
      @volatile private var value: T = _
      private var postForce: List[T => Unit] = Nil

      def force: T = {
        var result = value
        if (result != null) result
        else
          this.synchronized {
            if (value == null) value = t()
            result = value
            t = null
            postForce.foreach(_.apply(result))
            postForce = Nil
            result
          }
      }

      def onForce(f: T => Unit): Unit = {
        if (value != null) f(value)
        else
          this.synchronized {
            if (value != null) f(value)
            else postForce = f :: postForce
          }
      }

      override def toString = if (value == null) "<?>" else value.toString
    }
  }

}

object BTypes {

  /** A marker for strings that represent class internal names. Ideally the type would be incompatible with String, for
    * example by making it a value class. But that would create overhead in a Collection[InternalName].
    */
  type InternalName = String

  /** Metadata about a ClassBType, used by the inliner.
    *
    * More information may be added in the future to enable more elaborate inline heuristics. Note that this class
    * should contain information that can only be obtained from the ClassSymbol. Information that can be computed from
    * the ClassNode should be added to the call graph instead.
    *
    * @param isEffectivelyFinal
    *   True if the class cannot have subclasses: final classes, module classes.
    *
    * @param sam
    *   If this class is a SAM type, the SAM's "\$name\$descriptor".
    *
    * @param methodInfos
    *   The [[MethodInlineInfo]]s for the methods declared in this class. The map is indexed by the string
    *   s"\$name\$descriptor" (to disambiguate overloads).
    *
    * @param warning
    *   Contains an warning message if an error occurred when building this InlineInfo, for example if some classfile
    *   could not be found on the classpath. This warning can be reported later by the inliner.
    */
  final case class InlineInfo(
      isEffectivelyFinal: Boolean,
      sam: Option[String],
      methodInfos: collection.SortedMap[(String, String), MethodInlineInfo],
      warning: Option[ClassInlineInfoWarning]
  ) {
    lazy val methodInfosSorted: IndexedSeq[((String, String), MethodInlineInfo)] = {
      val result = new Array[((String, String), MethodInlineInfo)](methodInfos.size)
      var i = 0
      methodInfos.foreachEntry { (ownerAndName, info) =>
        result(i) = (ownerAndName, info)
        i += 1
      }
      scala.util.Sorting.quickSort(result)(Ordering.by(_._1))
      unsafeWrapArray(result)
    }
  }

  val EmptyInlineInfo =
    InlineInfo(isEffectivelyFinal = false, sam = None, methodInfos = SortedMap.empty, warning = None)

  /** Metadata about a method, used by the inliner.
    *
    * @param effectivelyFinal
    *   True if the method cannot be overridden (in Scala)
    * @param annotatedInline
    *   True if the method is annotated `@inline`
    * @param annotatedNoInline
    *   True if the method is annotated `@noinline`
    */
  final case class MethodInlineInfo(
      effectivelyFinal: Boolean = false,
      annotatedInline: Boolean = false,
      annotatedNoInline: Boolean = false
  )

  // no static way (without symbol table instance) to get to nme.ScalaATTR / ScalaSignatureATTR
  val ScalaAttributeName = "Scala"
  val ScalaSigAttributeName = "ScalaSig"

  // when inlining, local variable names of the callee are prefixed with the name of the callee method
  val InlinedLocalVariablePrefixMaxLength = 128
}
