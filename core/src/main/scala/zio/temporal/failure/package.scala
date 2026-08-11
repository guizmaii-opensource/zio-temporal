package zio.temporal

import zio.temporal.json.ZTemporalCodec

package object failure {
  type ApplicationFailure   = io.temporal.failure.ApplicationFailure
  type ActivityFailure      = io.temporal.failure.ActivityFailure
  type ChildWorkflowFailure = io.temporal.failure.ChildWorkflowFailure
  type TimeoutFailure       = io.temporal.failure.TimeoutFailure
  type CanceledFailure      = io.temporal.failure.CanceledFailure

  implicit class ZioTemporalApplicationFailureSyntax(private val self: ApplicationFailure) extends AnyVal {
    def getDetailsAs[E: TypeIsSpecified: ZTemporalCodec]: E =
      self.getDetails.get(ZTemporalCodec[E].klass, ZTemporalCodec[E].genericType)
  }

  implicit class ZioTemporalTimeoutFailureSyntax(private val self: TimeoutFailure) extends AnyVal {
    def getLastHeartbeatDetailsAs[E: TypeIsSpecified: ZTemporalCodec]: E =
      self.getLastHeartbeatDetails.get(ZTemporalCodec[E].klass, ZTemporalCodec[E].genericType)
  }
}
