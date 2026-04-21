package zio.temporal.json

import com.google.protobuf.ByteString
import io.temporal.api.common.v1.Payload
import io.temporal.common.converter.DataConverterException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import zio.json.JsonCodec

import java.nio.charset.StandardCharsets

class ZioJsonPayloadConverterSpec extends AnyWordSpec with Matchers {

  import ZioJsonPayloadConverterSpec._

  "ZioJsonPayloadConverter" should {

    "round-trip a registered ground type" in {
      val registry = new CodecRegistry()
      registry.register(ZTemporalCodec[User])
      val converter = new ZioJsonPayloadConverter(registry)

      val payload = converter.toData(User(1, "alice")).orElseThrow(() => new AssertionError("expected non-empty"))
      payload.getData.toStringUtf8 shouldEqual """{"id":1,"name":"alice"}"""

      val decoded = converter.fromData(payload, classOf[User], classOf[User])
      decoded shouldEqual User(1, "alice")
    }

    "round-trip a registered parameterized type via ParameterizedType key" in {
      val registry = new CodecRegistry()
      registry.register(ZTemporalCodec[User])
      registry.register(ZTemporalCodec[List[User]])
      val converter = new ZioJsonPayloadConverter(registry)

      val value   = List(User(1, "a"), User(2, "b"))
      val payload = converter.toData(value).orElseThrow(() => new AssertionError("expected non-empty"))

      val listCodec = ZTemporalCodec[List[User]]
      val decoded   =
        converter.fromData(payload, classOf[List[User]].asInstanceOf[Class[List[User]]], listCodec.genericType)
      decoded shouldEqual value
    }

    "emit json/zio encoding metadata" in {
      val registry = new CodecRegistry()
      registry.register(ZTemporalCodec[User])
      val converter = new ZioJsonPayloadConverter(registry)

      val payload = converter.toData(User(1, "alice")).orElseThrow(() => new AssertionError("expected non-empty"))
      payload.getMetadataOrThrow("encoding").toStringUtf8 shouldEqual "json/zio"
    }

    "return empty Optional for null values" in {
      val converter = new ZioJsonPayloadConverter(new CodecRegistry())
      converter.toData(null).isPresent shouldEqual false
    }

    "fail with DataConverterException when encoding an unregistered type" in {
      val converter = new ZioJsonPayloadConverter(new CodecRegistry())
      a[DataConverterException] should be thrownBy converter.toData(User(1, "a"))
    }

    "fail with DataConverterException when decoding an unregistered type" in {
      val converter = new ZioJsonPayloadConverter(new CodecRegistry())
      val payload   = Payload
        .newBuilder()
        .putMetadata("encoding", ByteString.copyFromUtf8("json/zio"))
        .setData(ByteString.copyFromUtf8("""{"id":1,"name":"a"}"""))
        .build()
      a[DataConverterException] should be thrownBy converter.fromData(payload, classOf[User], classOf[User])
    }

    "fail with DataConverterException on malformed JSON" in {
      val registry = new CodecRegistry()
      registry.register(ZTemporalCodec[User])
      val converter = new ZioJsonPayloadConverter(registry)
      val payload   = Payload
        .newBuilder()
        .putMetadata("encoding", ByteString.copyFromUtf8("json/zio"))
        .setData(ByteString.copyFrom("not valid json", StandardCharsets.UTF_8))
        .build()
      a[DataConverterException] should be thrownBy converter.fromData(payload, classOf[User], classOf[User])
    }
  }

  "CodecRegistry" should {
    "track registered size" in {
      val r = new CodecRegistry()
      r.size shouldEqual 0
      r.register(ZTemporalCodec[User])
      r.size shouldEqual 1
      r.register(ZTemporalCodec[List[User]])
      r.size shouldEqual 2
    }

    "return null for unregistered lookups" in {
      val r = new CodecRegistry()
      r.encoderForClass(classOf[User]) shouldBe null
      r.decoderForType(classOf[User]) shouldBe null
    }

    "key List[User] and List[Org] distinctly by genericType" in {
      val r = new CodecRegistry()
      r.register(ZTemporalCodec[List[User]])
      r.decoderForType(ZTemporalCodec[List[User]].genericType) should not be null
      r.decoderForType(ZTemporalCodec[List[Org]].genericType) shouldBe null
    }

    "encoderForClass walks implemented interfaces when no superclass matches" in {
      val r = new CodecRegistry()
      r.register(ZTemporalCodec[InterfaceWalkShape])
      // `InterfaceWalkRectangle` was never registered directly; its registered ancestor is the sealed trait.
      r.encoderForClass(classOf[InterfaceWalkRectangle]) should not be null
    }

    "encoderForClass walks transitive interfaces" in {
      val r = new CodecRegistry()
      r.register(ZTemporalCodec[InterfaceWalkMarker])
      // `InterfaceWalkTaggedImpl` implements `InterfaceWalkTagged`, which extends `InterfaceWalkMarker`.
      r.encoderForClass(classOf[InterfaceWalkTaggedImpl]) should not be null
    }
  }
}

// Fixtures for the interface-walk tests (names prefixed `InterfaceWalk` to avoid collision with `Shape`
// in `ZTemporalCodecSpec`, which lives in the same package).

private sealed trait InterfaceWalkShape derives zio.json.JsonCodec
private final case class InterfaceWalkRectangle(w: Double, h: Double) extends InterfaceWalkShape

private trait InterfaceWalkMarker
private trait InterfaceWalkTagged extends InterfaceWalkMarker
private object InterfaceWalkMarker {
  given zio.json.JsonEncoder[InterfaceWalkMarker] = zio.json.JsonEncoder.string.contramap(_.toString)
  given zio.json.JsonDecoder[InterfaceWalkMarker] =
    zio.json.JsonDecoder.string.map(_ => new InterfaceWalkTaggedImpl("x"))
}
private final class InterfaceWalkTaggedImpl(val tag: String) extends InterfaceWalkTagged

object ZioJsonPayloadConverterSpec {
  final case class User(id: Int, name: String) derives JsonCodec
  final case class Org(name: String) derives JsonCodec
}
