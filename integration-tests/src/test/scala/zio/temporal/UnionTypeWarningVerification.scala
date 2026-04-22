package zio.temporal

import zio.temporal.fixture._
import zio.temporal.workflow._

/** This file verifies that the type erasure warning system works correctly.
  *
  * When compiling this file, you should see:
  *   - WARNING for ProblematicUnionWorkflow.processWithAnyOrNull (Any erased to Object)
  *   - WARNING for every PrimitiveUnionWorkflow method (Scala primitives in `A | Null` erase to Object)
  *   - NO WARNING for ConcreteUnionWorkflow.processWithStringOrNull (String stays a reference type)
  *
  * Compile this file and check the output to verify the warning system.
  */
object UnionTypeWarningVerification {

  def testAnyOrNullWarning(): Unit = {
    val workflow: ZWorkflowStub.Of[ProblematicUnionWorkflow] = null

    // This SHOULD generate a warning because Any | Null would be erased to Object
    ZWorkflowStub.execute(workflow.processWithAnyOrNull("test"))
  }

  def testConcreteTypesNoWarning(): Unit = {
    val workflow: ZWorkflowStub.Of[ConcreteUnionWorkflow] = null

    // This should NOT generate a warning because String | Null keeps a reference slot
    ZWorkflowStub.execute(workflow.processWithStringOrNull("test"))
  }

  def testPrimitiveOrNullWarnings(): Unit = {
    val workflow: ZWorkflowStub.Of[PrimitiveUnionWorkflow] = null

    // Every one of these SHOULD generate a warning — a primitive in `A | Null`
    // erases to java.lang.Object at the JVM signature level.
    ZWorkflowStub.execute(workflow.processWithIntOrNull(42))
    ZWorkflowStub.query(workflow.withLongOrNull(42L))
    ZWorkflowStub.query(workflow.withBooleanOrNull(true))
    ZWorkflowStub.query(workflow.withDoubleOrNull(1.0d))
    ZWorkflowStub.query(workflow.withFloatOrNull(1.0f))
    ZWorkflowStub.query(workflow.withShortOrNull(1.toShort))
    ZWorkflowStub.query(workflow.withByteOrNull(1.toByte))
    ZWorkflowStub.query(workflow.withCharOrNull('a'))
  }
}
