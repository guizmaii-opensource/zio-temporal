package zio.temporal

import zio._
import zio.logging.backend.SLF4J
import zio.temporal.fixture._
import zio.temporal.testkit._
import zio.temporal.worker._
import zio.temporal.workflow._
import zio.test._

object UnionTypeWorkflowSpec extends BaseTemporalSpec {
  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    testEnvironment ++ Runtime.removeDefaultLoggers ++ SLF4J.slf4j

  override val spec = suite("Union Type Support")(
    test("workflow method accepts String | Null parameter with non-null value") {
      val taskQueue = "union-string-queue"

      for {
        workflowId <- ZIO.randomWith(_.nextUUID)
        _          <- ZTestWorkflowEnvironment.newWorker(taskQueue) @@
               ZWorker.addWorkflow[StringOrNullWorkflowImpl].fromClass
        _        <- ZTestWorkflowEnvironment.setup()
        workflow <- ZTestWorkflowEnvironment.newWorkflowStub[StringOrNullWorkflow](
                      ZWorkflowOptions
                        .withWorkflowId(workflowId.toString)
                        .withTaskQueue(taskQueue)
                        .withWorkflowRunTimeout(10.second)
                    )
        result <- ZWorkflowStub.execute(workflow.processWithStringOrNull("test"))
      } yield assertTrue(result == "string-value: test")
    }.provideTestWorkflowEnv,
    test("workflow method accepts newtype | Null parameter with non-null value") {
      import NewtypeWorkflow._
      val taskQueue = "union-newtype-queue"

      for {
        workflowId <- ZIO.randomWith(_.nextUUID)
        _          <- ZTestWorkflowEnvironment.newWorker(taskQueue) @@
               ZWorker.addWorkflow[NewtypeWorkflowImpl].fromClass
        _        <- ZTestWorkflowEnvironment.setup()
        workflow <- ZTestWorkflowEnvironment.newWorkflowStub[NewtypeWorkflow](
                      ZWorkflowOptions
                        .withWorkflowId(workflowId.toString)
                        .withTaskQueue(taskQueue)
                        .withWorkflowRunTimeout(10.second)
                    )
        testId = TestId("tenant-123")
        result <- ZWorkflowStub.execute(workflow.processWithNewtypeOrNull(testId))
      } yield assertTrue(result == "newtype-value: tenant-123")
    }.provideTestWorkflowEnv,
    test("workflow method accepts Int | Null parameter with non-null value") {
      val taskQueue = "union-int-queue"

      for {
        workflowId <- ZIO.randomWith(_.nextUUID)
        _          <- ZTestWorkflowEnvironment.newWorker(taskQueue) @@
               ZWorker.addWorkflow[IntOrNullWorkflowImpl].fromClass
        _        <- ZTestWorkflowEnvironment.setup()
        workflow <- ZTestWorkflowEnvironment.newWorkflowStub[IntOrNullWorkflow](
                      ZWorkflowOptions
                        .withWorkflowId(workflowId.toString)
                        .withTaskQueue(taskQueue)
                        .withWorkflowRunTimeout(10.second)
                    )
        result <- ZWorkflowStub.execute(workflow.processWithIntOrNull(42))
      } yield assertTrue(result == 42)
    }.provideTestWorkflowEnv,
    test("workflow query method returns String | Null") {
      val taskQueue = "union-query-queue"

      for {
        workflowId <- ZIO.randomWith(_.nextUUID)
        _          <- ZTestWorkflowEnvironment.newWorker(taskQueue) @@
               ZWorker.addWorkflow[StringOrNullWorkflowImpl].fromClass
        _        <- ZTestWorkflowEnvironment.setup()
        workflow <- ZTestWorkflowEnvironment.newWorkflowStub[StringOrNullWorkflow](
                      ZWorkflowOptions
                        .withWorkflowId(workflowId.toString)
                        .withTaskQueue(taskQueue)
                        .withWorkflowRunTimeout(10.second)
                    )
        _      <- ZWorkflowStub.start(workflow.processWithStringOrNull("query-test"))
        result <- ZWorkflowStub.query(workflow.getLastValue)
      } yield assertTrue(result == "query-test")
    }.provideTestWorkflowEnv
  )
}
