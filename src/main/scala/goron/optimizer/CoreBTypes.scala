/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc. dba Akka
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package goron.optimizer

import scala.tools.asm.{Handle, Opcodes}
import goron.optimizer.BTypes.InternalName

abstract class CoreBTypes extends PerRunInit {
  val bTypes: BTypes
  import bTypes._

  def boxedClasses: Set[ClassBType]
  def boxedClassOfPrimitive: Map[PrimitiveBType, ClassBType]

  def srNothingRef              : ClassBType
  def srNullRef                 : ClassBType
  def ObjectRef                 : ClassBType
  def StringRef                 : ClassBType
  def PredefRef                 : ClassBType
  def jlCloneableRef            : ClassBType
  def jiSerializableRef         : ClassBType
  def jlIllegalArgExceptionRef  : ClassBType
  def juHashMapRef              : ClassBType
  def juMapRef                  : ClassBType
  def jliCallSiteRef            : ClassBType
  def jliLambdaMetafactoryRef   : ClassBType
  def jliMethodTypeRef          : ClassBType
  def jliSerializedLambdaRef    : ClassBType
  def jliMethodHandleRef        : ClassBType
  def jliMethodHandlesLookupRef : ClassBType
  def srBoxesRunTimeRef         : ClassBType
  def srBoxedUnitRef            : ClassBType

  def srBoxesRuntimeBoxToMethods   : Map[BType, MethodNameAndType]
  def srBoxesRuntimeUnboxToMethods : Map[BType, MethodNameAndType]

  def javaBoxMethods   : Map[InternalName, MethodNameAndType]
  def javaUnboxMethods : Map[InternalName, MethodNameAndType]

  def predefAutoBoxMethods   : Map[String, MethodBType]
  def predefAutoUnboxMethods : Map[String, MethodBType]

  def srRefCreateMethods : Map[InternalName, MethodNameAndType]
  def srRefZeroMethods   : Map[InternalName, MethodNameAndType]

  def primitiveBoxConstructors : Map[InternalName, MethodNameAndType]
  def srRefConstructors        : Map[InternalName, MethodNameAndType]
  def tupleClassConstructors   : Map[InternalName, MethodNameAndType]

  def lambdaMetaFactoryMetafactoryHandle    : Handle
  def lambdaMetaFactoryAltMetafactoryHandle : Handle
  def lambdaDeserializeBootstrapHandle      : Handle
}

/**
 * Initializes CoreBTypes from well-known internal class names (no compiler symbols needed).
 * This is goron's replacement for CoreBTypesFromSymbols.
 */
abstract class CoreBTypesFromClassfile extends CoreBTypes {
  val bTypes: BTypes
  import bTypes._

  // Helper: create a ClassBType by internal name. The ClassBType info will be lazily
  // resolved from classfiles via the BTypesFromClassfile mechanism.
  protected def classBType(internalName: String): ClassBType =
    ClassBType(internalName, internalName, fromSymbol = false) { (res, iname) =>
      // Info will be filled in lazily by BTypesFromClassfile when needed
      import goron.optimizer.BackendReporting.NoClassBTypeInfoMissingBytecode
      Left(NoClassBTypeInfoMissingBytecode(BackendReporting.ClassNotFound(iname, definedInJavaSource = false)))
    }

  // Well-known class types
  lazy val srNothingRef             : ClassBType = classBType("scala/runtime/Nothing$")
  lazy val srNullRef                : ClassBType = classBType("scala/runtime/Null$")
  lazy val ObjectRef                : ClassBType = classBType("java/lang/Object")
  lazy val StringRef                : ClassBType = classBType("java/lang/String")
  lazy val PredefRef                : ClassBType = classBType("scala/Predef$")
  lazy val jlCloneableRef           : ClassBType = classBType("java/lang/Cloneable")
  lazy val jiSerializableRef        : ClassBType = classBType("java/io/Serializable")
  lazy val jlIllegalArgExceptionRef : ClassBType = classBType("java/lang/IllegalArgumentException")
  lazy val juHashMapRef             : ClassBType = classBType("java/util/HashMap")
  lazy val juMapRef                 : ClassBType = classBType("java/util/Map")
  lazy val jliCallSiteRef           : ClassBType = classBType("java/lang/invoke/CallSite")
  lazy val jliLambdaMetafactoryRef  : ClassBType = classBType("java/lang/invoke/LambdaMetafactory")
  lazy val jliMethodTypeRef         : ClassBType = classBType("java/lang/invoke/MethodType")
  lazy val jliSerializedLambdaRef   : ClassBType = classBType("java/lang/invoke/SerializedLambda")
  lazy val jliMethodHandleRef       : ClassBType = classBType("java/lang/invoke/MethodHandle")
  lazy val jliMethodHandlesLookupRef: ClassBType = classBType("java/lang/invoke/MethodHandles$Lookup")
  lazy val srBoxesRunTimeRef        : ClassBType = classBType("scala/runtime/BoxesRunTime")
  lazy val srBoxedUnitRef           : ClassBType = classBType("scala/runtime/BoxedUnit")
  lazy val srLambdaDeserialize      : ClassBType = classBType("scala/runtime/LambdaDeserialize")

  // Boxed types
  private lazy val boxedVoid      = classBType("java/lang/Void")
  private lazy val boxedBoolean   = classBType("java/lang/Boolean")
  private lazy val boxedByte      = classBType("java/lang/Byte")
  private lazy val boxedShort     = classBType("java/lang/Short")
  private lazy val boxedCharacter = classBType("java/lang/Character")
  private lazy val boxedInteger   = classBType("java/lang/Integer")
  private lazy val boxedLong      = classBType("java/lang/Long")
  private lazy val boxedFloat     = classBType("java/lang/Float")
  private lazy val boxedDouble    = classBType("java/lang/Double")

  lazy val boxedClassOfPrimitive: Map[PrimitiveBType, ClassBType] = Map(
    UNIT   -> boxedVoid,
    BOOL   -> boxedBoolean,
    BYTE   -> boxedByte,
    SHORT  -> boxedShort,
    CHAR   -> boxedCharacter,
    INT    -> boxedInteger,
    LONG   -> boxedLong,
    FLOAT  -> boxedFloat,
    DOUBLE -> boxedDouble,
  )

  lazy val boxedClasses: Set[ClassBType] = boxedClassOfPrimitive.values.toSet

  // Boxing/unboxing method descriptors (hardcoded from known JDK/Scala signatures)
  private val primitiveInfo: List[(String, PrimitiveBType, String, String)] = List(
    // (primName, primBType, boxedInternalName, unboxMethodName)
    ("Boolean", BOOL,   "java/lang/Boolean",   "booleanValue"),
    ("Byte",    BYTE,   "java/lang/Byte",      "byteValue"),
    ("Short",   SHORT,  "java/lang/Short",     "shortValue"),
    ("Char",    CHAR,   "java/lang/Character",  "charValue"),
    ("Int",     INT,    "java/lang/Integer",    "intValue"),
    ("Long",    LONG,   "java/lang/Long",       "longValue"),
    ("Float",   FLOAT,  "java/lang/Float",      "floatValue"),
    ("Double",  DOUBLE, "java/lang/Double",     "doubleValue"),
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
  lazy val javaBoxMethods: Map[InternalName, MethodNameAndType] = primitiveInfo.map {
    case (_, prim, boxed, _) =>
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
  lazy val predefAutoBoxMethods: Map[String, MethodBType] = primitiveInfo.map {
    case (name, prim, boxed, _) =>
      val methodName = name.toLowerCase + "2" + name
      methodName -> MethodBType(Array(prim), classBType(boxed))
  }.toMap

  // Boolean2boolean -> (Ljava/lang/Boolean;)Z
  lazy val predefAutoUnboxMethods: Map[String, MethodBType] = primitiveInfo.map {
    case (name, prim, boxed, _) =>
      val methodName = name + "2" + name.toLowerCase
      methodName -> MethodBType(Array(classBType(boxed)), prim)
  }.toMap

  // Ref class methods
  private val refClasses: List[(String, PrimitiveBType)] = List(
    ("scala/runtime/BooleanRef", BOOL),
    ("scala/runtime/ByteRef",    BYTE),
    ("scala/runtime/ShortRef",   SHORT),
    ("scala/runtime/CharRef",    CHAR),
    ("scala/runtime/IntRef",     INT),
    ("scala/runtime/LongRef",    LONG),
    ("scala/runtime/FloatRef",   FLOAT),
    ("scala/runtime/DoubleRef",  DOUBLE),
    ("scala/runtime/ObjectRef",  ObjectRef.asInstanceOf[PrimitiveBType]),  // special case, see below
  )

  private val refClassesProper: List[(String, BType)] = List(
    ("scala/runtime/BooleanRef", BOOL),
    ("scala/runtime/ByteRef",    BYTE),
    ("scala/runtime/ShortRef",   SHORT),
    ("scala/runtime/CharRef",    CHAR),
    ("scala/runtime/IntRef",     INT),
    ("scala/runtime/LongRef",    LONG),
    ("scala/runtime/FloatRef",   FLOAT),
    ("scala/runtime/DoubleRef",  DOUBLE),
    ("scala/runtime/ObjectRef",  ObjectRef),
  )

  lazy val srRefCreateMethods: Map[InternalName, MethodNameAndType] = refClassesProper.map {
    case (refClass, elemType) =>
      val desc = MethodBType(Array(elemType), classBType(refClass))
      refClass -> MethodNameAndType("create", desc)
  }.toMap

  lazy val srRefZeroMethods: Map[InternalName, MethodNameAndType] = refClassesProper.map {
    case (refClass, _) =>
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
    jliLambdaMetafactoryRef.internalName, "metafactory",
    MethodBType(
      Array(jliMethodHandlesLookupRef, StringRef, jliMethodTypeRef, jliMethodTypeRef, jliMethodHandleRef, jliMethodTypeRef),
      jliCallSiteRef
    ).descriptor,
    /* itf = */ false)

  lazy val lambdaMetaFactoryAltMetafactoryHandle: Handle = new Handle(
    Opcodes.H_INVOKESTATIC,
    jliLambdaMetafactoryRef.internalName, "altMetafactory",
    MethodBType(
      Array(jliMethodHandlesLookupRef, StringRef, jliMethodTypeRef, ArrayBType(ObjectRef)),
      jliCallSiteRef
    ).descriptor,
    /* itf = */ false)

  lazy val lambdaDeserializeBootstrapHandle: Handle = new Handle(
    Opcodes.H_INVOKESTATIC,
    srLambdaDeserialize.internalName, "bootstrap",
    MethodBType(
      Array(jliMethodHandlesLookupRef, StringRef, jliMethodTypeRef, ArrayBType(jliMethodHandleRef)),
      jliCallSiteRef
    ).descriptor,
    /* itf = */ false)
}
