package zio.temporal.json

import io.temporal.common.converter._

/** Entry point for constructing a [[DataConverter]] that uses zio-json for all non-Temporal-internal payloads.
  *
  * The ordering of underlying payload converters matters — Temporal's `DefaultDataConverter` tries each in order:
  *
  *   1. [[NullPayloadConverter]] — null values
  *   1. [[ByteArrayPayloadConverter]] — raw byte buffers
  *   1. [[ProtobufJsonPayloadConverter]] — Temporal-internal types like `WorkflowExecution`
  *   1. [[ZioJsonPayloadConverter]] — everything else
  *
  * The trailing zio-json converter is where all user-defined workflow/activity inputs and outputs are handled, using
  * the `registry` to look up encoders and decoders.
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
