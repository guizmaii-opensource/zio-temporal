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

  "ZioJsonPayloadConverter — parameterized-type collision" should {

    // Regression: registering `List[User]` and `List[Org]` used to put both encoders under
    // `classOf[List]` in `byClass`, with the second registration silently overwriting the first. Encoding
    // a `List[User]` would then pick up the `List[Org]` encoder and produce JSON with Org's schema,
    // corrupting the payload. The fix: parameterized-type codecs live only in `byType`; the encode path
    // falls back to per-element dispatch for generic containers.

    "round-trip List[User] even when List[Org] is also registered (the two do not collide)" in {
      val registry = new CodecRegistry()
        .register(ZTemporalCodec[User])
        .register(ZTemporalCodec[Org])
        .register(ZTemporalCodec[List[User]])
        .register(ZTemporalCodec[List[Org]]) // registered LAST — would have overwritten List[User] pre-fix.
      val converter = new ZioJsonPayloadConverter(registry)

      val users   = List(User(1, "alice"), User(2, "bob"))
      val payload = converter.toData(users).orElseThrow(() => new AssertionError("expected non-empty"))
      payload.getData.toStringUtf8 shouldEqual """[{"id":1,"name":"alice"},{"id":2,"name":"bob"}]"""

      val listUserCodec = ZTemporalCodec[List[User]]
      val decoded       =
        converter.fromData(
          payload,
          classOf[List[User]].asInstanceOf[Class[List[User]]],
          listUserCodec.genericType
        )
      decoded shouldEqual users
    }

    "round-trip List[Org] alongside List[User]" in {
      val registry = new CodecRegistry()
        .register(ZTemporalCodec[User])
        .register(ZTemporalCodec[Org])
        .register(ZTemporalCodec[List[User]])
        .register(ZTemporalCodec[List[Org]])
      val converter = new ZioJsonPayloadConverter(registry)

      val orgs    = List(Org("acme"), Org("globex"))
      val payload = converter.toData(orgs).orElseThrow(() => new AssertionError("expected non-empty"))
      payload.getData.toStringUtf8 shouldEqual """[{"name":"acme"},{"name":"globex"}]"""

      val listOrgCodec = ZTemporalCodec[List[Org]]
      val decoded      =
        converter.fromData(
          payload,
          classOf[List[Org]].asInstanceOf[Class[List[Org]]],
          listOrgCodec.genericType
        )
      decoded shouldEqual orgs
    }

    "round-trip List[User] when only the element codec is registered (no List codec needed on encode)" in {
      // Encode side: `encoderForClass(classOf[$colon$colon])` misses → container dispatch kicks in,
      // each element uses the `User` codec. Decode side: needs `ZTemporalCodec[List[User]]` for the
      // generic-type lookup, so we register that too.
      val registry = new CodecRegistry()
        .register(ZTemporalCodec[User])
        .register(ZTemporalCodec[List[User]])
      val converter = new ZioJsonPayloadConverter(registry)

      val users   = List(User(1, "alice"))
      val payload = converter.toData(users).orElseThrow(() => new AssertionError("expected non-empty"))
      payload.getData.toStringUtf8 shouldEqual """[{"id":1,"name":"alice"}]"""

      val decoded = converter.fromData(
        payload,
        classOf[List[User]].asInstanceOf[Class[List[User]]],
        ZTemporalCodec[List[User]].genericType
      )
      decoded shouldEqual users
    }

    "encode an empty List[User] to `[]`" in {
      val registry  = new CodecRegistry().register(ZTemporalCodec[User]).register(ZTemporalCodec[List[User]])
      val converter = new ZioJsonPayloadConverter(registry)
      val payload   = converter.toData(List.empty[User]).orElseThrow(() => new AssertionError("empty"))
      payload.getData.toStringUtf8 shouldEqual "[]"
    }

    "container dispatch also handles Vector[User] and Set[User]" in {
      val registry  = new CodecRegistry().register(ZTemporalCodec[User])
      val converter = new ZioJsonPayloadConverter(registry)

      val vec = converter.toData(Vector(User(1, "a"))).orElseThrow(() => new AssertionError("vec"))
      vec.getData.toStringUtf8 shouldEqual """[{"id":1,"name":"a"}]"""

      val set = converter.toData(Set(User(1, "a"))).orElseThrow(() => new AssertionError("set"))
      set.getData.toStringUtf8 shouldEqual """[{"id":1,"name":"a"}]"""
    }

    "container dispatch handles Some(User) and None" in {
      val registry  = new CodecRegistry().register(ZTemporalCodec[User])
      val converter = new ZioJsonPayloadConverter(registry)

      val some = converter.toData(Some(User(1, "a"))).orElseThrow(() => new AssertionError("some"))
      some.getData.toStringUtf8 shouldEqual """{"id":1,"name":"a"}"""

      val none = converter.toData(None).orElseThrow(() => new AssertionError("none"))
      none.getData.toStringUtf8 shouldEqual "null"
    }

    "container dispatch handles Map[String, User]" in {
      val registry  = new CodecRegistry().register(ZTemporalCodec[User])
      val converter = new ZioJsonPayloadConverter(registry)
      val payload   =
        converter
          .toData(Map("alice" -> User(1, "alice"), "bob" -> User(2, "bob")))
          .orElseThrow(() => new AssertionError("map"))
      val body = payload.getData.toStringUtf8
      // Map iteration order is insertion-stable for immutable.Map; the JSON should contain both entries.
      body should include(""""alice":{"id":1,"name":"alice"}""")
      body should include(""""bob":{"id":2,"name":"bob"}""")
    }

    "nested List[List[User]] round-trips" in {
      val registry = new CodecRegistry()
        .register(ZTemporalCodec[User])
        .register(ZTemporalCodec[List[User]])
        .register(ZTemporalCodec[List[List[User]]])
      val converter = new ZioJsonPayloadConverter(registry)

      val nested  = List(List(User(1, "a")), List(User(2, "b"), User(3, "c")))
      val payload = converter.toData(nested).orElseThrow(() => new AssertionError("nested"))
      payload.getData.toStringUtf8 shouldEqual
        """[[{"id":1,"name":"a"}],[{"id":2,"name":"b"},{"id":3,"name":"c"}]]"""

      val decoded = converter.fromData(
        payload,
        classOf[List[List[User]]].asInstanceOf[Class[List[List[User]]]],
        ZTemporalCodec[List[List[User]]].genericType
      )
      decoded shouldEqual nested
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
