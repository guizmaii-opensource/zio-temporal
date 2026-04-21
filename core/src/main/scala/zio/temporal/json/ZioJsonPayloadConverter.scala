package zio.temporal.json

import com.google.protobuf.ByteString
import io.temporal.api.common.v1.Payload
import io.temporal.common.converter._
import zio.json.{JsonDecoder, JsonEncoder}

import java.io.OutputStreamWriter
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
        // Streaming encode: zio-json writes UTF-8 JSON straight into Protobuf's `ByteString.Output` via a
        // `WriteWriter` → `OutputStreamWriter` bridge, skipping the `String` intermediate that
        // `encodeJson.toString` + `ByteString.copyFrom(string, UTF_8)` would have produced. For any
        // non-trivial payload this saves a full copy of the encoded bytes (one round-trip String → bytes).
        val byteStringOutput = ByteString.newOutput()
        val writer           = new OutputStreamWriter(byteStringOutput, StandardCharsets.UTF_8)
        val write            = new zio.json.internal.WriteWriter(writer)
        encodeValue(v, write)
        writer.flush() // drain the OutputStreamWriter's internal char buffer into the byte output
        Optional.of(
          Payload
            .newBuilder()
            .putMetadata(EncodingMetadataKey, EncodingPayload)
            .setData(byteStringOutput.toByteString)
            .build()
        )
    }

  /** Encodes a single value into the given `Write`. Looks up the encoder by the value's runtime class via the registry;
    * falls back to per-element dispatch for generic containers (see [[encodeContainer]]) since every `List[X]` shares
    * the same raw runtime class and no single registered encoder can be correct for all of them.
    */
  private def encodeValue(v: Any, out: zio.json.internal.Write): Unit = {
    val cls     = v.getClass
    val encoder = registry.encoderForClass(cls)
    if (encoder ne null) {
      encoder.asInstanceOf[JsonEncoder[Any]].unsafeEncode(v, None, out)
    } else if (!encodeContainer(v, out)) {
      throw new DataConverterException(
        s"No ZTemporalCodec registered for runtime class `${cls.getName}`.\n" +
          "Likely causes:\n" +
          "  1. A workflow or activity interface using this type was not registered with the client.\n" +
          "     Fix: `ZWorkflowClientOptions.make @@ ZWorkflowClientOptions.withCodecRegistry(\n" +
          "       new CodecRegistry().addInterface[YourWorkflow].addInterface[YourActivity])`,\n" +
          "     or on the type itself: `final case class YourType(...) derives ZTemporalCodec`.\n" +
          "  2. An untyped stub (`ZWorkflowStub.Untyped` / `ZActivityStub.Untyped`) is being used, which\n" +
          "     bypasses the compile-time codec gate. Pre-register any types you pass through untyped\n" +
          "     stubs with `registry.register(ZTemporalCodec[YourType])`.\n" +
          s"Currently registered: [${registry.registeredTypeNames.mkString(", ")}]"
      )
    }
  }

  /** Encodes well-known generic containers (`List`, `Vector`, `Seq`, `Set`, `Option`, `Map[String, _]`) by iterating
    * their elements and dispatching each to its own runtime-class-based encoder.
    *
    * This covers the case where the caller registered `ZTemporalCodec[List[Foo]]` but — because every `List[X]` shares
    * the same raw class — the registry's `byClass` map couldn't pin that codec to `List[Foo]` uniquely (see
    * `CodecRegistry#register`). We still need to produce a valid encoded list, so we recurse on each element's actual
    * runtime class and let the registered element codec do the element-level encoding.
    *
    * Returns `true` if the value was recognized and encoded; `false` to let the caller emit its "not registered" error.
    */
  private def encodeContainer(v: Any, out: zio.json.internal.Write): Boolean = v match {
    case iterable: Iterable[_] =>
      iterable match {
        case map: Map[_, _] =>
          out.write('{')
          var first = true
          map.foreach { case (k, value) =>
            if (first) first = false else out.write(',')
            // JSON object keys must be strings; fall back to `.toString` for non-String keys.
            out.write('"')
            out.write(k.toString)
            out.write('"')
            out.write(':')
            encodeValue(value, out)
          }
          out.write('}')
          true
        case _ =>
          out.write('[')
          var first = true
          iterable.foreach { element =>
            if (first) first = false else out.write(',')
            encodeValue(element, out)
          }
          out.write(']')
          true
      }
    case Some(inner) =>
      encodeValue(inner, out)
      true
    case None =>
      out.write('n', 'u', 'l', 'l')
      true
    case _ => false
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
