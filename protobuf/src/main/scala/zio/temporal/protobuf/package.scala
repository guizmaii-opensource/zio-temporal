package zio.temporal

import scalapb.{GeneratedMessage, GeneratedSealedOneof}
import zio.json.{JsonDecoder, JsonEncoder}
import zio.temporal.json.ZTemporalCodec

import scala.reflect.ClassTag

/** Protobuf-aware conveniences.
  *
  * The important members here are [[scalapbMessageZTemporalCodec]] and [[scalapbSealedOneofZTemporalCodec]], which
  * auto-derive a [[ZTemporalCodec]] for any ScalaPB-generated [[scalapb.GeneratedMessage]] or
  * [[scalapb.GeneratedSealedOneof]]. The zio-json `JsonEncoder`/`JsonDecoder` carried by those codecs are deliberate
  * stubs that throw if invoked — at runtime, [[ScalapbPayloadConverter]] handles protobuf types before
  * [[zio.temporal.json.ZioJsonPayloadConverter]] is reached in [[ProtobufDataConverter]]'s chain, so the stubs are
  * never called.
  *
  * '''Scope warning — the `JsonEncoder`/`JsonDecoder` bridges are deliberately not provided at the package-object
  * level.''' They live in [[ScalapbJsonImplicits]] and must be imported explicitly, because making them globally
  * implicit-visible would hijack every zio-json call site the user has for protobuf types — including any unrelated
  * logging/HTTP use — and cause it to throw at runtime.
  *
  * Use them only where zio-json's own derivation needs to see encoder/decoder evidence for a protobuf type inside a
  * generic structure (e.g. `Either[Foo, MyProtobufMsg]`).
  */
package object protobuf {

  private[protobuf] def passthroughMessage[A](implicit ct: ClassTag[A]): String =
    s"JsonEncoder/JsonDecoder for protobuf type ${ct.runtimeClass.getName} should not be invoked — " +
      "ScalapbPayloadConverter handles protobuf types before ZioJsonPayloadConverter in the DataConverter chain."

  private[protobuf] def passthroughEncoder[A](implicit ct: ClassTag[A]): JsonEncoder[A] = {
    val msg = passthroughMessage[A]
    JsonEncoder.string.contramap[A](_ => throw new UnsupportedOperationException(msg))
  }

  private[protobuf] def passthroughDecoder[A](implicit ct: ClassTag[A]): JsonDecoder[A] = {
    val msg = passthroughMessage[A]
    JsonDecoder.string.mapOrFail[A](_ => Left(msg))
  }

  /** `ZTemporalCodec[A]` for any `GeneratedMessage`. Safe to import globally — the zio-json encoder/decoder the codec
    * carries are internal and never leak into the user's wider implicit scope.
    */
  implicit def scalapbMessageZTemporalCodec[A <: GeneratedMessage](implicit ct: ClassTag[A]): ZTemporalCodec[A] =
    ZTemporalCodec.make(passthroughEncoder[A], passthroughDecoder[A])

  implicit def scalapbSealedOneofZTemporalCodec[A <: GeneratedSealedOneof](implicit ct: ClassTag[A])
    : ZTemporalCodec[A] =
    ZTemporalCodec.make(passthroughEncoder[A], passthroughDecoder[A])
}
