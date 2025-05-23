package zio.temporal.workflow

import zio.temporal.internalApi

trait ExtendsWorkflow[A] {}

object ExtendsWorkflow extends ExtendsWorkflowImplicits {
  def apply[A](implicit ev: ExtendsWorkflow[A]): ev.type = ev

  @internalApi
  object __zio_temporal_ExtendsWorkflowInstance extends ExtendsWorkflow[Any]
}
