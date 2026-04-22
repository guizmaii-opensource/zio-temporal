package zio.temporal.fixture

import zio.temporal._

/** This file is used to manually verify that type erasure warnings are still generated for problematic union types.
  *
  * When compiled, this should produce warnings for:
  *   - ProblematicUnionWorkflow.processWithAnyOrNull (Any | Null would be erased to Object)
  *   - PrimitiveUnionWorkflow.* (Scala primitives in `A | Null` erase to Object at the JVM level)
  *
  * And should NOT produce warnings for:
  *   - ConcreteUnionWorkflow.processWithStringOrNull (String | Null stays a reference type)
  */

// This workflow uses Any | Null which SHOULD generate a warning
// because Any is erased to Object
@workflowInterface
trait ProblematicUnionWorkflow {
  @workflowMethod
  def processWithAnyOrNull(value: Any | Null): String
}

class ProblematicUnionWorkflowImpl extends ProblematicUnionWorkflow {
  override def processWithAnyOrNull(value: Any | Null): String = {
    if (value == null) "null-value"
    else s"value: $value"
  }
}

// This workflow uses reference types which should NOT generate warnings
@workflowInterface
trait ConcreteUnionWorkflow {
  @workflowMethod
  def processWithStringOrNull(value: String | Null): String
}

class ConcreteUnionWorkflowImpl extends ConcreteUnionWorkflow {
  override def processWithStringOrNull(value: String | Null): String = {
    if (value == null) "null-value" else s"string-value: $value"
  }
}

// Every Scala primitive in a `A | Null` parameter erases to java.lang.Object at the JVM signature level
// (primitive slots can't hold `null`), so each of these SHOULD generate a warning.
@workflowInterface
trait PrimitiveUnionWorkflow {
  @workflowMethod
  def processWithIntOrNull(value: Int | Null): Int

  @queryMethod
  def withLongOrNull(value: Long | Null): Long

  @queryMethod
  def withBooleanOrNull(value: Boolean | Null): Boolean

  @queryMethod
  def withDoubleOrNull(value: Double | Null): Double

  @queryMethod
  def withFloatOrNull(value: Float | Null): Float

  @queryMethod
  def withShortOrNull(value: Short | Null): Short

  @queryMethod
  def withByteOrNull(value: Byte | Null): Byte

  @queryMethod
  def withCharOrNull(value: Char | Null): Char
}

class PrimitiveUnionWorkflowImpl extends PrimitiveUnionWorkflow {
  override def processWithIntOrNull(value: Int | Null): Int = value match {
    case null   => -1
    case i: Int => i
  }

  override def withLongOrNull(value: Long | Null): Long = value match {
    case null    => -1L
    case l: Long => l
  }

  override def withBooleanOrNull(value: Boolean | Null): Boolean = value match {
    case null       => false
    case b: Boolean => b
  }

  override def withDoubleOrNull(value: Double | Null): Double = value match {
    case null      => -1.0d
    case d: Double => d
  }

  override def withFloatOrNull(value: Float | Null): Float = value match {
    case null     => -1.0f
    case f: Float => f
  }

  override def withShortOrNull(value: Short | Null): Short = value match {
    case null     => (-1).toShort
    case s: Short => s
  }

  override def withByteOrNull(value: Byte | Null): Byte = value match {
    case null    => (-1).toByte
    case b: Byte => b
  }

  override def withCharOrNull(value: Char | Null): Char = value match {
    case null    => ' '
    case c: Char => c
  }
}
