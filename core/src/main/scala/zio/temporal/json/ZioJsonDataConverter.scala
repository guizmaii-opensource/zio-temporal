package zio.temporal.json

import io.temporal.common.converter._

/** Entry point for constructing a [[DataConverter]] that uses zio-json for all user-owned payloads.
  *
  * The ordering of underlying payload converters matters — Temporal's `DefaultDataConverter` tries each in order:
  *
  *   1. [[NullPayloadConverter]] — null values
  *   1. [[ByteArrayPayloadConverter]] — raw byte buffers
  *   1. [[ProtobufJsonPayloadConverter]] — Temporal-internal types like `WorkflowExecution`
  *   1. [[ZioJsonPayloadConverter]] — user workflow/activity inputs & outputs (encoding `json/zio`)
  *   1. [[JacksonJsonPayloadConverter]] — trailing fallback for Temporal's own `json/plain` payloads. Temporal's test
  *      server and some history/completion events still write `json/plain` via Jackson, so dropping this converter
  *      entirely breaks replay of those messages. The converter uses a plain Jackson `ObjectMapper` with no Scala
  *      module — user Scala types never reach it because `ZioJsonPayloadConverter` (`json/zio`) claims them first on
  *      the decode side, and on the encode side the user's value always round-trips as `json/zio`.
  */
object ZioJsonDataConverter {

  /** Build a `DataConverter` backed by the supplied [[CodecRegistry]]. */
  def make(registry: CodecRegistry): DataConverter =
    new DefaultDataConverter(
      new NullPayloadConverter(),
      new ByteArrayPayloadConverter(),
      new ProtobufJsonPayloadConverter(),
      new ZioJsonPayloadConverter(registry),
      new JacksonJsonPayloadConverter()
    )
}
