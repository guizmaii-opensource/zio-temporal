package zio.temporal.json

import io.temporal.common.converter._

/** Entry point for constructing a [[DataConverter]] that uses zio-json for all user-owned payloads. Jackson is
  * deliberately excluded from the chain — zio-temporal is Scala-only and zio-json covers the full encoding surface.
  *
  * Chain ordering (Temporal's `DefaultDataConverter` tries each in order):
  *
  *   1. [[NullPayloadConverter]] — null values
  *   1. [[ByteArrayPayloadConverter]] — raw byte buffers
  *   1. [[ProtobufJsonPayloadConverter]] — Temporal-internal protobuf types like `WorkflowExecution`
  *   1. [[ZioJsonPayloadConverter]] — everything else (encoding `json/zio`)
  */
object ZioJsonDataConverter {

  /** Build a `DataConverter` backed by the supplied [[CodecRegistry]]. */
  def make(registry: CodecRegistry): DataConverter =
    new DefaultDataConverter(
      new NullPayloadConverter(),
      new ByteArrayPayloadConverter(),
      new ProtobufJsonPayloadConverter(),
      new ZioJsonPayloadConverter(registry)
    )
}
