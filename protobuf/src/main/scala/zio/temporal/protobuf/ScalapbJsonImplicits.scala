package zio.temporal.protobuf

import scalapb.{GeneratedMessage, GeneratedSealedOneof}
import zio.json.{JsonDecoder, JsonEncoder}

import scala.reflect.ClassTag

/** Opt-in zio-json encoder/decoder bridges for ScalaPB-generated types.
  *
  * These are needed only when you want zio-json to derive a codec for a generic structure that contains a protobuf type
  * — e.g. `JsonEncoder[Either[MyProtobufMsg, String]]`. Importing this object brings `JsonEncoder[A <:
  * GeneratedMessage]` and `JsonDecoder[A <: GeneratedMessage]` (and the sealed-oneof variants) into implicit scope.
  *
  * ''Do not import globally.'' These encoders/decoders are throwing stubs — they are meant to live inside a
  * `ZTemporalCodec` whose actual serialization is handled by [[ScalapbPayloadConverter]] earlier in the chain. If they
  * leak into the user's wider zio-json usage (logs, HTTP handlers, etc.) protobuf serialization will throw at runtime.
  *
  * Import it locally where needed:
  *
  * {{{
  *   import zio.temporal.protobuf.ScalapbJsonImplicits._
  * }}}
  */
object ScalapbJsonImplicits {

  given scalapbMessageJsonEncoder[A <: GeneratedMessage](using ClassTag[A]): JsonEncoder[A] =
    passthroughEncoder[A]

  given scalapbMessageJsonDecoder[A <: GeneratedMessage](using ClassTag[A]): JsonDecoder[A] =
    passthroughDecoder[A]

  given scalapbSealedOneofJsonEncoder[A <: GeneratedSealedOneof](using ClassTag[A]): JsonEncoder[A] =
    passthroughEncoder[A]

  given scalapbSealedOneofJsonDecoder[A <: GeneratedSealedOneof](using ClassTag[A]): JsonDecoder[A] =
    passthroughDecoder[A]
}
