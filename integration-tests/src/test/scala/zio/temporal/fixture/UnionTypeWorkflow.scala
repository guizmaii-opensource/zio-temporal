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

// Newtype | Null workflow - using real zio-prelude Subtype
object NewtypeWorkflow {
  import zio.prelude.Subtype

  // Real zio-prelude newtype
  type TestId = TestId.Type
  object TestId extends Subtype[String]
}

@workflowInterface
trait NewtypeWorkflow {
  import NewtypeWorkflow._

  @workflowMethod
  def processWithNewtypeOrNull(value: TestId | Null): String
}

class NewtypeWorkflowImpl extends NewtypeWorkflow {
  import NewtypeWorkflow._

  override def processWithNewtypeOrNull(value: TestId | Null): String = {
    value match {
      case null                 => "null-newtype"
      case nonNullValue: TestId => s"newtype-value: ${TestId.unwrap(nonNullValue)}"
      case _                    => sys.error("unexpected case")
    }
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
      case null              => -1
      case nonNullValue: Int => nonNullValue
    }
  }
}
