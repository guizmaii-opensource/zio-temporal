package zio.temporal.fixture

import zio.temporal._
import zio.temporal.json.ZTemporalCodec
import zio.temporal.workflow._

import java.time.Instant
import java.util.UUID

/** Captured by `ZWorkflow.sideEffect`. The non-deterministic pieces (`id`, `at`) are recorded once into workflow
  * history on the first run and replayed identically on every subsequent history replay.
  */
final case class SideEffectSnapshot(
  id:   UUID,
  at:   Instant,
  seq:  Long,
  tags: List[String])
    derives ZTemporalCodec

@workflowInterface
trait SideEffectWorkflow {
  @workflowMethod
  def capture(seed: Int): SideEffectSnapshot
}

class SideEffectWorkflowImpl extends SideEffectWorkflow {

  override def capture(seed: Int): SideEffectSnapshot =
    // `ZWorkflow.sideEffect` summons `ZTemporalCodec[SideEffectSnapshot]` and round-trips the captured value
    // through the Temporal DataConverter (into workflow history on first run, out on replay). Any codec gap
    // corrupts determinism silently — the workflow either fails replay with a type error or decodes to garbage.
    ZWorkflow.sideEffect(() =>
      SideEffectSnapshot(
        id = UUID.randomUUID(),
        at = Instant.now(),
        seq = seed.toLong,
        tags = List("alpha", "beta", s"seed-$seed")
      )
    )
}
