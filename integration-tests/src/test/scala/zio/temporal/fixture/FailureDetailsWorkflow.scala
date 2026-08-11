package zio.temporal.fixture

import zio._
import zio.temporal._
import zio.temporal.activity._
import zio.temporal.failure.ApplicationFailure
import zio.temporal.failure._ // for `getDetailsAs` extension on ApplicationFailure
import zio.temporal.json.ZTemporalCodec
import zio.temporal.workflow._

/** Structured details attached to an `ApplicationFailure` and read back by the workflow via `getDetailsAs[UserError]`.
  */
final case class UserError(userId: String, code: Int, tags: List[String]) derives ZTemporalCodec

@activityInterface
trait FailureDetailsActivity {
  def failWith(userId: String, code: Int): Unit
}

class FailureDetailsActivityImpl extends FailureDetailsActivity {

  override def failWith(userId: String, code: Int): Unit =
    // Activities throw `ApplicationFailure` to signal typed failures. `details` goes through the DataConverter
    // on the way out and must round-trip back through the registered `ZTemporalCodec[UserError]` when the
    // workflow unwraps it. Using `newNonRetryableFailure` so the activity fails fast instead of exhausting
    // the default retry policy in the test run.
    throw ApplicationFailure.newNonRetryableFailure(
      s"simulated failure for user=$userId",
      "UserError",
      UserError(userId, code, tags = List("primary", s"code-$code"))
    )
}

@workflowInterface
trait FailureDetailsWorkflow {
  @workflowMethod
  def captureFailure(userId: String, code: Int): UserError
}

class FailureDetailsWorkflowImpl extends FailureDetailsWorkflow {

  private val activity = ZWorkflow.newActivityStub[FailureDetailsActivity](
    ZActivityOptions.withStartToCloseTimeout(5.seconds)
  )

  override def captureFailure(userId: String, code: Int): UserError =
    try {
      ZActivityStub.execute(activity.failWith(userId, code))
      throw new AssertionError("activity should have thrown ApplicationFailure")
    } catch {
      case af: io.temporal.failure.ActivityFailure =>
        af.getCause match {
          case appFailure: io.temporal.failure.ApplicationFailure =>
            appFailure.getDetailsAs[UserError]
          case other =>
            throw new AssertionError(s"expected ApplicationFailure cause, got ${Option(other).map(_.getClass.getName)}")
        }
    }
}
