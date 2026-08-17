package zio.temporal.state

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class ZWorkflowStateSpec extends AnyWordSpec with Matchers {
  enum Status {
    case Created, InProcess, Succeeded, Failed
  }

  "ZWorkflowState.Required.updateWhen" should {
    "update when match" in {
      val state = ZWorkflowState.make[Status](Status.InProcess)

      state.updateWhen { case Status.InProcess =>
        Status.Succeeded
      }

      state.snapshot shouldEqual Status.Succeeded
    }

    "save state when don't match" in {
      val state = ZWorkflowState.make[Status](Status.Failed)

      state.updateWhen { case Status.InProcess =>
        Status.Succeeded
      }

      state.snapshot shouldEqual Status.Failed
    }
  }
}
