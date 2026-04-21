package zio.temporal.json

import com.google.protobuf.ByteString
import io.temporal.api.common.v1.Payload
import io.temporal.common.converter.{DataConverterException, PayloadConverter}
import zio.json.ast.Json

import java.lang.reflect.{Field, Type}
import java.nio.charset.StandardCharsets
import java.util.Optional

/** Handles Temporal Java SDK internal POJOs that leak into the `DataConverter` chain as part of workflow mechanics the
  * user never writes codecs for.
  *
  * The only class currently needed is `io.temporal.internal.sync.WorkflowRetryerInternal$SerializableRetryOptions`,
  * which `Workflow.retry` serializes through a `mutableSideEffect` marker so that retry configuration is recorded in
  * the workflow history (see `WorkflowRetryerInternal.getRetryOptionsSideEffect`). The SDK's default chain has Jackson
  * as the last-resort converter and handles this transparently; since we've deliberately excluded Jackson (zio-temporal
  * is Scala-only and zio-json covers the full user-facing surface), we have to encode this one class explicitly.
  *
  * The allow-list is kept tight on purpose: open reflection over `io.temporal.*` would accidentally claim legitimate
  * user-facing types like `io.temporal.common.RetryOptions` (builder-pattern, nested `Duration`) whose round-trip
  * semantics are not ours to define.
  */
final class TemporalInternalsPayloadConverter extends PayloadConverter {

  import TemporalInternalsPayloadConverter._

  override def getEncodingType: String = EncodingName

  override def toData(value: Any): Optional[Payload] =
    value match {
      case null => Optional.empty()
      case v    =>
        codecFor(v.getClass) match {
          case null  => Optional.empty() // not one of ours — let the next converter try
          case codec =>
            val json  = codec.encode(v)
            val bytes = json.getBytes(StandardCharsets.UTF_8)
            Optional.of(
              Payload
                .newBuilder()
                .putMetadata(EncodingMetadataKey, EncodingPayload)
                .setData(ByteString.copyFrom(bytes))
                .build()
            )
        }
    }

  override def fromData[T](content: Payload, valueClass: Class[T], valueType: Type): T = {
    val codec = codecFor(valueClass)
    if (codec eq null) {
      throw new DataConverterException(
        s"TemporalInternalsPayloadConverter received a payload with encoding `$EncodingName` for class " +
          s"`${valueClass.getName}`, but that class is not in the allow-list. " +
          s"Allow-list: [${AllowList.mkString(", ")}]."
      )
    }
    val body = content.getData.toStringUtf8
    codec.decode(body).asInstanceOf[T]
  }
}

object TemporalInternalsPayloadConverter {

  /** Encoding name written into payload metadata. Distinct from `json/zio` so the decode path routes correctly. */
  final val EncodingName: String = "json/temporal-internal"

  private val EncodingMetadataKey: String = "encoding"
  private val EncodingPayload: ByteString = ByteString.copyFrom(EncodingName, StandardCharsets.UTF_8)

  /** Minimal shape of an internal-type codec: encode a value to a JSON string, decode a JSON string back. */
  private trait InternalCodec {
    def encode(value: Any): String
    def decode(body: String): Any
  }

  /** Reflective codec for a plain POJO. Encodes each declared field as a JSON property keyed by the field's declared
    * name; decodes by instantiating via the no-arg constructor and setting each field from the parsed JSON. Field
    * order is driven by `Class#getDeclaredFields` and cached once at init.
    */
  private final class ReflectiveCodec(klass: Class[_], fields: Array[Field]) extends InternalCodec {

    override def encode(value: Any): String = {
      val builder = new java.lang.StringBuilder(64)
      builder.append('{')
      var index     = 0
      var firstOut  = true
      while (index < fields.length) {
        val field    = fields(index)
        val rawValue = field.get(value)
        val jsonNull = rawValue == null
        if (jsonNull) {
          if (!firstOut) builder.append(',') else firstOut = false
          appendString(builder, field.getName)
          builder.append(":null")
        } else {
          if (!firstOut) builder.append(',') else firstOut = false
          appendString(builder, field.getName)
          builder.append(':')
          appendPrimitive(builder, field.getType, rawValue)
        }
        index += 1
      }
      builder.append('}')
      builder.toString
    }

    override def decode(body: String): Any = {
      val parsed = Json.decoder.decodeJson(body) match {
        case Right(Json.Obj(entries)) => entries
        case Right(other)             =>
          throw new DataConverterException(
            s"Expected JSON object for ${klass.getName} but got ${other.getClass.getSimpleName}."
          )
        case Left(err) =>
          throw new DataConverterException(s"Failed to parse ${klass.getName} payload: $err")
      }
      val ctor     = klass.getDeclaredConstructor()
      ctor.setAccessible(true)
      val instance = ctor.newInstance()
      val map      = parsed.toMap
      var index    = 0
      while (index < fields.length) {
        val field = fields(index)
        map.get(field.getName) match {
          case None | Some(Json.Null) => () // leave default; primitives keep their default value
          case Some(json)             => field.set(instance, fromJson(field.getType, json, field.getName))
        }
        index += 1
      }
      instance
    }

    private def appendString(builder: java.lang.StringBuilder, raw: String): Unit = {
      builder.append('"')
      var index = 0
      while (index < raw.length) {
        val character = raw.charAt(index)
        character match {
          case '"'  => builder.append("\\\"")
          case '\\' => builder.append("\\\\")
          case '\n' => builder.append("\\n")
          case '\r' => builder.append("\\r")
          case '\t' => builder.append("\\t")
          case '\b' => builder.append("\\b")
          case '\f' => builder.append("\\f")
          case character if character < 0x20 =>
            builder.append("\\u%04x".format(character.toInt))
          case character => builder.append(character)
        }
        index += 1
      }
      builder.append('"')
    }

    private def appendPrimitive(builder: java.lang.StringBuilder, fieldType: Class[_], rawValue: Any): Unit = {
      if ((fieldType eq java.lang.Long.TYPE) || (fieldType eq classOf[java.lang.Long])) {
        builder.append(rawValue.asInstanceOf[Long])
      } else if ((fieldType eq java.lang.Integer.TYPE) || (fieldType eq classOf[java.lang.Integer])) {
        builder.append(rawValue.asInstanceOf[Int])
      } else if ((fieldType eq java.lang.Double.TYPE) || (fieldType eq classOf[java.lang.Double])) {
        val doubleValue = rawValue.asInstanceOf[Double]
        if (doubleValue.isNaN || doubleValue.isInfinite) builder.append("null")
        else builder.append(doubleValue)
      } else if ((fieldType eq java.lang.Float.TYPE) || (fieldType eq classOf[java.lang.Float])) {
        val floatValue = rawValue.asInstanceOf[Float]
        if (floatValue.isNaN || floatValue.isInfinite) builder.append("null")
        else builder.append(floatValue)
      } else if ((fieldType eq java.lang.Boolean.TYPE) || (fieldType eq classOf[java.lang.Boolean])) {
        builder.append(rawValue.asInstanceOf[Boolean])
      } else if ((fieldType eq java.lang.Short.TYPE) || (fieldType eq classOf[java.lang.Short])) {
        builder.append(rawValue.asInstanceOf[Short].toInt)
      } else if ((fieldType eq java.lang.Byte.TYPE) || (fieldType eq classOf[java.lang.Byte])) {
        builder.append(rawValue.asInstanceOf[Byte].toInt)
      } else if (fieldType eq classOf[String]) {
        appendString(builder, rawValue.asInstanceOf[String])
      } else if (fieldType.isArray && (fieldType.getComponentType eq classOf[String])) {
        val arrayValue = rawValue.asInstanceOf[Array[String]]
        builder.append('[')
        var index     = 0
        while (index < arrayValue.length) {
          if (index > 0) builder.append(',')
          val element = arrayValue(index)
          if (element == null) builder.append("null")
          else appendString(builder, element)
          index += 1
        }
        builder.append(']')
      } else {
        throw new DataConverterException(
          s"TemporalInternalsPayloadConverter: unsupported field type `${fieldType.getName}` on " +
            s"`${klass.getName}`. Extend the reflective codec if a new Temporal internal type requires it."
        )
      }
    }

    private def fromJson(fieldType: Class[_], json: Json, fieldName: String): Any = {
      if ((fieldType eq java.lang.Long.TYPE) || (fieldType eq classOf[java.lang.Long])) {
        asNumber(json, fieldName).longValueExact
      } else if ((fieldType eq java.lang.Integer.TYPE) || (fieldType eq classOf[java.lang.Integer])) {
        asNumber(json, fieldName).intValueExact
      } else if ((fieldType eq java.lang.Double.TYPE) || (fieldType eq classOf[java.lang.Double])) {
        asNumber(json, fieldName).doubleValue
      } else if ((fieldType eq java.lang.Float.TYPE) || (fieldType eq classOf[java.lang.Float])) {
        asNumber(json, fieldName).floatValue
      } else if ((fieldType eq java.lang.Boolean.TYPE) || (fieldType eq classOf[java.lang.Boolean])) {
        json match {
          case Json.Bool(value) => value
          case other            =>
            throw new DataConverterException(
              s"Expected boolean for `${klass.getName}.$fieldName` but got ${other.getClass.getSimpleName}."
            )
        }
      } else if ((fieldType eq java.lang.Short.TYPE) || (fieldType eq classOf[java.lang.Short])) {
        asNumber(json, fieldName).shortValueExact
      } else if ((fieldType eq java.lang.Byte.TYPE) || (fieldType eq classOf[java.lang.Byte])) {
        asNumber(json, fieldName).byteValueExact
      } else if (fieldType eq classOf[String]) {
        json match {
          case Json.Str(value) => value
          case other           =>
            throw new DataConverterException(
              s"Expected string for `${klass.getName}.$fieldName` but got ${other.getClass.getSimpleName}."
            )
        }
      } else if (fieldType.isArray && (fieldType.getComponentType eq classOf[String])) {
        json match {
          case Json.Arr(elements) =>
            val out   = new Array[String](elements.length)
            var index = 0
            while (index < elements.length) {
              out(index) = elements(index) match {
                case Json.Str(value) => value
                case Json.Null       => null
                case other           =>
                  throw new DataConverterException(
                    s"Expected string element for `${klass.getName}.$fieldName[$index]` but got " +
                      s"${other.getClass.getSimpleName}."
                  )
              }
              index += 1
            }
            out
          case other =>
            throw new DataConverterException(
              s"Expected array for `${klass.getName}.$fieldName` but got ${other.getClass.getSimpleName}."
            )
        }
      } else {
        throw new DataConverterException(
          s"TemporalInternalsPayloadConverter: unsupported field type `${fieldType.getName}` on " +
            s"`${klass.getName}`."
        )
      }
    }

    private def asNumber(json: Json, fieldName: String): java.math.BigDecimal = json match {
      case Json.Num(value) => value
      case other           =>
        throw new DataConverterException(
          s"Expected number for `${klass.getName}.$fieldName` but got ${other.getClass.getSimpleName}."
        )
    }
  }

  /** Allow-listed class names. Kept as a constant so `fromData`'s diagnostic message stays accurate if the list grows.
    */
  private val AllowList: Array[String] = Array(
    "io.temporal.internal.sync.WorkflowRetryerInternal$SerializableRetryOptions"
  )

  /** Pre-resolve the codecs at class init. Missing classes (e.g. if Temporal refactors the SDK) are skipped and a
    * runtime miss simply falls through to the next converter — which will then emit its own diagnostic.
    */
  private val codecs: Map[Class[_], InternalCodec] = {
    val builder = Map.newBuilder[Class[_], InternalCodec]
    var index   = 0
    while (index < AllowList.length) {
      val className = AllowList(index)
      try {
        val klass  = Class.forName(className)
        val fields = klass.getDeclaredFields.filter { field =>
          val modifiers = field.getModifiers
          !java.lang.reflect.Modifier.isStatic(modifiers) && !field.isSynthetic
        }
        fields.foreach(_.setAccessible(true))
        builder += (klass -> new ReflectiveCodec(klass, fields))
      } catch {
        case _: ClassNotFoundException => ()
      }
      index += 1
    }
    builder.result()
  }

  private def codecFor(klass: Class[_]): InternalCodec | Null =
    codecs.getOrElse(klass, null)
}
