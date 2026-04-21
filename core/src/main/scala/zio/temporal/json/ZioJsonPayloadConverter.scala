package zio.temporal.json

import com.google.protobuf.ByteString
import io.temporal.api.common.v1.Payload
import io.temporal.common.converter._
import zio.json.{JsonDecoder, JsonEncoder}

import java.lang.reflect.Type
import java.nio.charset.StandardCharsets
import java.util.Optional

/** A Temporal [[PayloadConverter]] backed by zio-json and a [[CodecRegistry]].
  *
  * Encoding: looks up a `JsonEncoder` by the value's runtime class. Decoding: looks up a `JsonDecoder` by the requested
  * generic `Type` (not just raw class), so `List[Foo]` and `List[Bar]` dispatch distinctly.
  *
  * Throws [[DataConverterException]] with a specific message if a codec is missing — this should not happen for types
  * exercised via zio-temporal's typed stubs/workers (the compile-time gate ensures registration) but can occur for
  * untyped stubs or manual payload manipulation.
  */
final class ZioJsonPayloadConverter(registry: CodecRegistry) extends PayloadConverter {

  import ZioJsonPayloadConverter._

  override def getEncodingType: String = EncodingName

  override def toData(value: Any): Optional[Payload] =
    value match {
      case null => Optional.empty()
      case v    =>
        val cls     = v.getClass
        val encoder = registry.encoderForClass(cls)
        if (encoder eq null) {
          throw new DataConverterException(
            s"No ZTemporalCodec registered for runtime class `${cls.getName}`.\n" +
              "Likely causes:\n" +
              "  1. A workflow or activity interface using this type was not registered with the client.\n" +
              "     Fix: `ZWorkflowClientOptions.make @@ ZWorkflowClientOptions.withCodecRegistry(\n" +
              "       new CodecRegistry().addInterface[YourWorkflow].addInterface[YourActivity])`.\n" +
              "  2. An untyped stub (`ZWorkflowStub.Untyped` / `ZActivityStub.Untyped`) is being used, which\n" +
              "     bypasses the compile-time codec gate. Pre-register any types you pass through untyped\n" +
              "     stubs with `registry.register(ZTemporalCodec[YourType])`.\n" +
              s"Currently registered: [${registry.registeredTypeNames.mkString(", ")}]"
          )
        }
        // encodeJson returns a CharSequence (concretely a StringBuilder-like type); Protobuf's ByteString.copyFrom
        // only accepts String, so we must materialize.
        val json = encoder
          .asInstanceOf[JsonEncoder[Any]]
          .encodeJson(v, None)
          .toString
        Optional.of(
          Payload
            .newBuilder()
            .putMetadata(EncodingMetadataKey, EncodingPayload)
            .setData(ByteString.copyFrom(json, StandardCharsets.UTF_8))
            .build()
        )
    }

  override def fromData[T](content: Payload, valueClass: Class[T], valueType: Type): T = {
    // Try the parameterized Type first; fall back to the raw class for ground types.
    var decoder: JsonDecoder[_] | Null = registry.decoderForType(valueType)
    if (decoder eq null) decoder = registry.decoderForType(valueClass)
    if (decoder eq null) {
      throw new DataConverterException(
        s"No ZTemporalCodec registered for decode target `${valueType.getTypeName}` (raw `${valueClass.getName}`).\n" +
          "Likely causes:\n" +
          "  1. A workflow or activity interface using this type was not registered with the worker or client.\n" +
          "     Fix: `ZWorkflowClientOptions.make @@ ZWorkflowClientOptions.withCodecRegistry(\n" +
          "       new CodecRegistry().addInterface[YourWorkflow].addInterface[YourActivity])`.\n" +
          "  2. An untyped stub (`ZWorkflowStub.Untyped` / `ZActivityStub.Untyped`) is being used.\n" +
          "     Pre-register any types you query/receive through untyped stubs with\n" +
          "     `registry.register(ZTemporalCodec[YourType])`.\n" +
          s"Currently registered: [${registry.registeredTypeNames.mkString(", ")}]"
      )
    }
    val json   = content.getData.toStringUtf8
    val result = decoder.asInstanceOf[JsonDecoder[T]].decodeJson(json)
    result match {
      case Right(v)  => v
      case Left(err) => throw new DataConverterException(s"Failed to decode payload as ${valueType.getTypeName}: $err")
    }
  }
}

object ZioJsonPayloadConverter {

  /** The encoding name written into payload metadata. Distinct from Temporal's built-in `json/plain` (Jackson) and
    * `json/protobuf`.
    */
  final val EncodingName: String = "json/zio"

  private val EncodingMetadataKey: String = "encoding"
  private val EncodingPayload: ByteString = ByteString.copyFrom(EncodingName, StandardCharsets.UTF_8)
}
