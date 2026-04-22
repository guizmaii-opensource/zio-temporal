package zio.temporal.fixture

import zio.temporal._
import zio.temporal.json.ZTemporalCodec
import zio.temporal.workflow._

/** Accumulated structured state carried through multiple `mutableSideEffect` recordings. */
final case class MutableSummary(attempts: Int, tail: List[String]) derives ZTemporalCodec

@workflowInterface
trait MutableSideEffectWorkflow {
  @workflowMethod
  def run(seed: Int): MutableSummary
}

/** Exercises `ZWorkflow.mutableSideEffect[MutableSummary]`, the codec-driven workflow-determinism seam that
  * `TemporalInternalsPayloadConverter` was introduced for (retry configurations flow through this path internally as
  * `WorkflowRetryerInternal$SerializableRetryOptions`). Each `mutableSideEffect` call summons a
  * `ZTemporalCodec[MutableSummary]` and records the value into workflow history when it has changed — any codec gap
  * here corrupts determinism on replay or produces a type error.
  */
class MutableSideEffectWorkflowImpl extends MutableSideEffectWorkflow {

  override def run(seed: Int): MutableSummary = {
    val changed: (MutableSummary, MutableSummary) => Boolean = (a, b) => a != b

    // Three iterations, each recording a refreshed Summary. Because `updated` always reports true (values differ),
    // every iteration writes a fresh marker into history. On replay, each marker must decode back via the
    // registered codec.
    val first  = ZWorkflow.mutableSideEffect[MutableSummary](
      "summary",
      changed,
      () => MutableSummary(1, List(s"seed-$seed", "one"))
    )
    val second = ZWorkflow.mutableSideEffect[MutableSummary](
      "summary",
      changed,
      () => MutableSummary(first.attempts + 1, first.tail :+ "two")
    )
    val third  = ZWorkflow.mutableSideEffect[MutableSummary](
      "summary",
      changed,
      () => MutableSummary(second.attempts + 1, second.tail :+ "three")
    )
    third
  }
}
