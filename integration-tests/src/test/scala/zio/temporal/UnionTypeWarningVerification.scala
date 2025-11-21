package zio.temporal

import zio.temporal.fixture._
import zio.temporal.workflow._

/**
 * This file verifies that the type erasure warning system works correctly.
 *
 * When compiling this file, you should see:
 * - WARNING for ProblematicUnionWorkflow.processWithAnyOrNull (Any erased to Object)
 * - NO WARNING for ConcreteUnionWorkflow methods (concrete types are safe)
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

    // These should NOT generate warnings because String and Int are concrete types
    ZWorkflowStub.execute(workflow.processWithStringOrNull("test"))
    ZWorkflowStub.execute(workflow.processWithIntOrNull(42))
  }
}
