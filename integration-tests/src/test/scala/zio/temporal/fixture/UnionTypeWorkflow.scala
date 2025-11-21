package zio.temporal.fixture

import zio.temporal._

// String | Null workflow
@workflowInterface
trait StringOrNullWorkflow {
  @workflowMethod
  def processWithStringOrNull(value: String | Null): String

  @queryMethod
  def getLastValue: String | Null
}

class StringOrNullWorkflowImpl extends StringOrNullWorkflow {
  private var lastValue: String | Null = null

  override def processWithStringOrNull(value: String | Null): String = {
    lastValue = value
    if (value == null) "null-value" else s"string-value: $value"
  }

  override def getLastValue: String | Null = lastValue
}

// Type alias | Null workflow
object TypeAliasWorkflow {
  // Type alias for testing, similar to TenantId from zio-prelude Subtype
  // This simulates: type TenantId = TenantId.Type where TenantId.Type is String
  type TestId = String
  object TestId {
    def apply(value: String): TestId = value
    def unwrap(id: TestId): String = id
  }
}

@workflowInterface
trait TypeAliasWorkflow {
  import TypeAliasWorkflow._

  @workflowMethod
  def processWithTypeAliasOrNull(value: TestId | Null): String
}

class TypeAliasWorkflowImpl extends TypeAliasWorkflow {
  import TypeAliasWorkflow._

  override def processWithTypeAliasOrNull(value: TestId | Null): String = {
    if (value == null) "null-alias"
    else s"alias-value: ${TestId.unwrap(value)}"
  }
}

// Int | Null workflow
@workflowInterface
trait IntOrNullWorkflow {
  @workflowMethod
  def processWithIntOrNull(value: Int | Null): Int
}

class IntOrNullWorkflowImpl extends IntOrNullWorkflow {
  override def processWithIntOrNull(value: Int | Null): Int = {
    value match {
      case null => -1
      case nonNullValue: Int => nonNullValue
    }
  }
}
