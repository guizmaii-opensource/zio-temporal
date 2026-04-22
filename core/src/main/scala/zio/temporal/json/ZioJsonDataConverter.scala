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
  *   1. [[TemporalInternalsPayloadConverter]] — an allow-listed set of Temporal Java SDK internal POJOs (currently
  *      `WorkflowRetryerInternal$SerializableRetryOptions`) that leak into the chain via `Workflow.retry`'s
  *      `mutableSideEffect` and for which no user-provided codec exists. The upstream SDK relies on Jackson's
  *      reflective fallback here; we substitute a surgical reflective encoder.
  *   1. [[ZioJsonPayloadConverter]] encoding `json/zio` — user-owned payloads; claimed first on encode.
  *   1. [[ZioJsonPayloadConverter]] encoding `json/plain` — decode-only compatibility with recorded workflow histories
  *      that were originally written by the Jackson-based `DefaultDataConverter`. `json/plain` is just vanilla JSON, so
  *      the same registry decodes it correctly. Position matters: on encode, `DefaultDataConverter` stops at the first
  *      converter returning non-empty, so the `json/zio` instance wins and fresh payloads never get stamped
  *      `json/plain`.
  */
object ZioJsonDataConverter {

  /** Build a `DataConverter` backed by the supplied [[CodecRegistry]]. */
  def make(registry: CodecRegistry): DataConverter =
    new DefaultDataConverter(
      new NullPayloadConverter(),
      new ByteArrayPayloadConverter(),
      new ProtobufJsonPayloadConverter(),
      new TemporalInternalsPayloadConverter(),
      new ZioJsonPayloadConverter(registry),
      new ZioJsonPayloadConverter(registry, ZioJsonPayloadConverter.JsonPlainEncodingName)
    )
}
