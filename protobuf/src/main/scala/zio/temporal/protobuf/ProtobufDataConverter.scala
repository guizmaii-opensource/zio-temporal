package zio.temporal.protobuf

import io.temporal.common.converter._
import zio.temporal.json.{CodecRegistry, ZioJsonPayloadConverter}

object ProtobufDataConverter {

  /** Creates a [[DataConverter]] supporting user-defined protobuf generated types plus a zio-json fallback for
    * primitive and case-class-shaped payloads.
    *
    * @param registry
    *   the registry used by the trailing [[ZioJsonPayloadConverter]]. Typically populated by zio-temporal's
    *   client/worker registration macros.
    */
  def make(registry: CodecRegistry): DataConverter =
    new DefaultDataConverter(
      // order matters!
      new NullPayloadConverter(),
      new ByteArrayPayloadConverter(),
      new ProtobufJsonPayloadConverter(),
      new ScalapbPayloadConverter(),
      new ZioJsonPayloadConverter(registry) // zio-json fallback for non-protobuf types (primitives, case classes)
    )

}
