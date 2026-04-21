package zio.temporal.fixture

import zio.json.JsonCodec
import zio.temporal._
import zio.temporal.workflow._
import scala.reflect.ClassTag

final case class ParameterizedWorkflowOutput(message: String) derives JsonCodec

sealed trait ParameterizedChildWorkflowInput derives JsonCodec
object ParameterizedChildWorkflowInput {
  final case class Soda(kind: String)               extends ParameterizedChildWorkflowInput derives JsonCodec
  final case class Juice(kind: String, volume: Int) extends ParameterizedChildWorkflowInput derives JsonCodec
}

// NOTE: temporal won't deserialize correctly without the lower-bound type
trait ParameterizedChildWorkflow[Input <: ParameterizedChildWorkflowInput] {
  @workflowMethod
  def childTask(input: Input): ParameterizedWorkflowOutput
}

sealed trait ParameterizedWorkflowInput derives JsonCodec
object ParameterizedWorkflowInput {
  final case class Soda(kind: String)  extends ParameterizedWorkflowInput derives JsonCodec
  final case class Juice(kind: String) extends ParameterizedWorkflowInput derives JsonCodec
}

// NOTE: temporal won't deserialize correctly without the lower-bound type
trait ParameterizedWorkflow[Input <: ParameterizedWorkflowInput] {
  @workflowMethod
  def parentTask(input: Input): List[ParameterizedWorkflowOutput]
}

abstract class DelegatingParameterizedWorkflow[
  Input <: ParameterizedWorkflowInput,
  ChildInput <: ParameterizedChildWorkflowInput,
  ChildWorkflow <: ParameterizedChildWorkflow[ChildInput]: IsWorkflow: ClassTag]
    extends ParameterizedWorkflow[Input] {

  protected def constructChildInput(input: Input, randomData: Int): ChildInput

  private val logger         = ZWorkflow.makeLogger
  private val thisWorkflowId = ZWorkflow.info.workflowId

  override def parentTask(input: Input): List[ParameterizedWorkflowOutput] = {
    val someData = List(1, 2, 3)
    logger.info("Creating inputs...")
    val inputTasks = someData.map { randomData =>
      randomData -> constructChildInput(input, randomData)
    }

    logger.info("Creating child workflows...")
    // Create multiple parallel child workflows
    val taskRuns = ZAsync.foreachPar(inputTasks) { case (randomData, input) =>
      val child = ZWorkflow.newChildWorkflowStub[ChildWorkflow](
        ZChildWorkflowOptions.withWorkflowId(s"$thisWorkflowId/child/$randomData")
      )

      logger.info(s"Starting child workflow input=$input...")
      ZChildWorkflowStub.executeAsync(
        child.childTask(input)
      )
    }

    // Wait until completed
    taskRuns.run.getOrThrow
  }
}

@workflowInterface
trait SodaChildWorkflow extends ParameterizedChildWorkflow[ParameterizedChildWorkflowInput.Soda]

class SodaChildWorkflowImpl extends SodaChildWorkflow {
  override def childTask(input: ParameterizedChildWorkflowInput.Soda): ParameterizedWorkflowOutput = {
    ParameterizedWorkflowOutput(s"Providing with soda: ${input.kind}")
  }
}

@workflowInterface
trait JuiceChildWorkflow extends ParameterizedChildWorkflow[ParameterizedChildWorkflowInput.Juice]

class JuiceChildChildWorkflowImpl extends JuiceChildWorkflow {
  override def childTask(input: ParameterizedChildWorkflowInput.Juice): ParameterizedWorkflowOutput = {
    ParameterizedWorkflowOutput(s"Providing with ${input.kind} juice (${input.volume}L)")
  }
}

@workflowInterface
trait SodaWorkflow extends ParameterizedWorkflow[ParameterizedWorkflowInput.Soda]

class SodaWorkflowImpl
    extends DelegatingParameterizedWorkflow[
      ParameterizedWorkflowInput.Soda,
      ParameterizedChildWorkflowInput.Soda,
      SodaChildWorkflow
    ]
    with SodaWorkflow {

  override protected def constructChildInput(
    input:      ParameterizedWorkflowInput.Soda,
    randomData: Int
  ): ParameterizedChildWorkflowInput.Soda =
    ParameterizedChildWorkflowInput.Soda(input.kind)
}

@workflowInterface
trait JuiceWorkflow extends ParameterizedWorkflow[ParameterizedWorkflowInput.Juice]

class JuiceWorkflowImpl
    extends DelegatingParameterizedWorkflow[
      ParameterizedWorkflowInput.Juice,
      ParameterizedChildWorkflowInput.Juice,
      JuiceChildWorkflow
    ]
    with JuiceWorkflow {

  override protected def constructChildInput(
    input:      ParameterizedWorkflowInput.Juice,
    randomData: Int
  ): ParameterizedChildWorkflowInput.Juice =
    ParameterizedChildWorkflowInput.Juice(input.kind, randomData)
}
