package zio.temporal

import zio._
import zio.temporal.fixture._
import zio.temporal.testkit._
import zio.temporal.worker._
import zio.temporal.workflow._
import zio.test._

import java.util.UUID

/** Regression tests pinning codec coverage on the three workflow-correctness seams flagged by the PR #203 review:
  *
  *   1. `ZWorkflow.sideEffect[StructuredType]` — round-trip a non-trivial case class through a `sideEffect` recording.
  *   1. `ZWorkflow.mutableSideEffect[StructuredType]` — codec-carrying marker that the `TemporalInternalsPayloadConverter`
  *      fix was introduced for. (The review originally asked for `getLastCompletionResult[R]`; that API is populated only
  *      for cron workflows, not continue-as-new, so we cover the same `ZTemporalCodec[R]`-driven recording path through
  *      `mutableSideEffect`, which is the seam the retry-options bug exposed.)
  *   1. `ApplicationFailure.getDetailsAs[T]` — structured error-details round-trip from activity to workflow.
  *
  * Each test exercises the full DataConverter pipeline inside the in-process Temporal test server.
  */
object CodecSeamSpec extends BaseTemporalSpec {

  override val spec: Spec[TestEnvironment with Scope, Any] =
    suite("CodecSeam")(
      test("sideEffect round-trips a structured case class through workflow history") {
        val taskQueue = "codec-seam-side-effect"

        for {
          uuid   <- ZIO.randomWith(_.nextUUID)
          _      <- ZTestWorkflowEnvironment.newWorker(taskQueue) @@
                      ZWorker.addWorkflow[SideEffectWorkflowImpl].fromClass

          _      <- ZTestWorkflowEnvironment.setup()

          stub   <- ZTestWorkflowEnvironment.newWorkflowStub[SideEffectWorkflow](
                      ZWorkflowOptions
                        .withWorkflowId(s"side-effect/$uuid")
                        .withTaskQueue(taskQueue)
                    )
          result <- ZWorkflowStub.execute(stub.capture(42))
        } yield assertTrue(
          // The non-deterministic `id` and `at` are recorded once by `sideEffect` and returned verbatim, so we
          // assert only the deterministic fields. Any codec gap in the `sideEffect` path would either corrupt
          // these fields or fail the workflow on replay.
          result.seq == 42L,
          result.tags == List("alpha", "beta", "seed-42"),
          result.id != null,
          result.at != null
        )
      },
      test("mutableSideEffect round-trips structured state across multiple recordings") {
        val taskQueue = "codec-seam-mutable-side-effect"

        for {
          uuid   <- ZIO.randomWith(_.nextUUID)
          _      <- ZTestWorkflowEnvironment.newWorker(taskQueue) @@
                      ZWorker.addWorkflow[MutableSideEffectWorkflowImpl].fromClass

          _      <- ZTestWorkflowEnvironment.setup()

          stub   <- ZTestWorkflowEnvironment.newWorkflowStub[MutableSideEffectWorkflow](
                      ZWorkflowOptions
                        .withWorkflowId(s"mutable-side-effect/$uuid")
                        .withTaskQueue(taskQueue)
                    )
          result <- ZWorkflowStub.execute(stub.run(7))
        } yield assertTrue(
          // Three iterations, each carrying structured state forward. The final `MutableSummary` value must
          // exactly match what each recording produced — every marker is a round-trip through the registered
          // `ZTemporalCodec[MutableSummary]`.
          result == MutableSummary(3, List("seed-7", "one", "two", "three"))
        )
      },
      test("ApplicationFailure.getDetailsAs decodes structured details via the registered codec") {
        ZTestWorkflowEnvironment.activityRunOptionsWithZIO[Any] { implicit activityOptions =>
          val taskQueue = "codec-seam-failure-details"

          for {
            uuid   <- ZIO.randomWith(_.nextUUID)
            _      <- ZTestWorkflowEnvironment.newWorker(taskQueue) @@
                        ZWorker.addWorkflow[FailureDetailsWorkflowImpl].fromClass @@
                        ZWorker.addActivityImplementation(new FailureDetailsActivityImpl)

            _      <- ZTestWorkflowEnvironment.setup()

            stub   <- ZTestWorkflowEnvironment.newWorkflowStub[FailureDetailsWorkflow](
                        ZWorkflowOptions
                          .withWorkflowId(s"failure-details/$uuid")
                          .withTaskQueue(taskQueue)
                      )
            result <- ZWorkflowStub.execute(stub.captureFailure("alice", 99))
          } yield assertTrue(
            // The whole UserError — including the `tags` list — must survive encode-on-throw in the activity
            // and decode-on-catch in the workflow via the registered `ZTemporalCodec[UserError]`.
            result == UserError(userId = "alice", code = 99, tags = List("primary", "code-99"))
          )
        }
      }
    ).provideTestWorkflowEnv @@ TestAspect.flaky
}
