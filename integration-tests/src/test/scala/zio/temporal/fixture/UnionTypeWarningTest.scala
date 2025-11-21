package zio.temporal.fixture

import zio.temporal._

/**
 * This file is used to manually verify that type erasure warnings are still
 * generated for problematic union types.
 *
 * When compiled, this should produce warnings for:
 * - ProblematicUnionWorkflow.processWithAnyOrNull (Any | Null would be erased to Object)
 *
 * And should NOT produce warnings for:
 * - ConcreteUnionWorkflow.processWithStringOrNull (String | Null)
 * - ConcreteUnionWorkflow.processWithIntOrNull (Int | Null)
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

// This workflow uses concrete types which should NOT generate warnings
@workflowInterface
trait ConcreteUnionWorkflow {
  @workflowMethod
  def processWithStringOrNull(value: String | Null): String

  @workflowMethod
  def processWithIntOrNull(value: Int | Null): Int
}

class ConcreteUnionWorkflowImpl extends ConcreteUnionWorkflow {
  override def processWithStringOrNull(value: String | Null): String = {
    if (value == null) "null-value" else s"string-value: $value"
  }

  override def processWithIntOrNull(value: Int | Null): Int = {
    value match {
      case null => -1
      case i: Int => i
    }
  }
}
