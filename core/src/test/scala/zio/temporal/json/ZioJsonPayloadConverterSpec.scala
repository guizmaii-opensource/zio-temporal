package zio.temporal.json

import com.google.protobuf.ByteString
import io.temporal.api.common.v1.Payload
import io.temporal.common.converter.DataConverterException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import zio.json.{DeriveJsonCodec, JsonCodec}

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

    // Scala 3 erases a workflow parameter typed `Int | Null` (or any primitive-union) to `java.lang.Object`
    // at the JVM signature level. The Temporal worker then reflects on that method and calls
    // `fromData(..., classOf[Object], classOf[Object])`. Without the JSON-shape fallback below, no codec
    // matches and the worker task fails with "No ZTemporalCodec registered for decode target
    // `java.lang.Object`". The fix decodes the payload in a content-aware way and returns a boxed primitive
    // (or nested collection) so that the workflow body's `case _: Int` pattern match still succeeds.
    "decode to java.lang.Integer when Object is the target and the payload is a valid int" in {
      val converter = new ZioJsonPayloadConverter(new CodecRegistry())
      val payload   = rawPayload("42")
      val decoded   = converter.fromData(payload, classOf[Object], classOf[Object])
      decoded shouldEqual java.lang.Integer.valueOf(42)
      decoded shouldBe a[java.lang.Integer]
    }

    "decode to java.lang.Long when Object is the target and the payload exceeds Int range" in {
      val converter = new ZioJsonPayloadConverter(new CodecRegistry())
      val payload   = rawPayload("9999999999")
      val decoded   = converter.fromData(payload, classOf[Object], classOf[Object])
      decoded shouldEqual java.lang.Long.valueOf(9999999999L)
      decoded shouldBe a[java.lang.Long]
    }

    "decode to java.lang.Double when Object is the target and the payload has a fractional part" in {
      val converter = new ZioJsonPayloadConverter(new CodecRegistry())
      val payload   = rawPayload("3.14")
      val decoded   = converter.fromData(payload, classOf[Object], classOf[Object])
      decoded shouldEqual java.lang.Double.valueOf(3.14)
      decoded shouldBe a[java.lang.Double]
    }

    "decode to String when Object is the target and the payload is a JSON string" in {
      val converter = new ZioJsonPayloadConverter(new CodecRegistry())
      val payload   = rawPayload("\"hello\"")
      val decoded   = converter.fromData(payload, classOf[Object], classOf[Object])
      decoded shouldEqual "hello"
    }

    "decode to java.lang.Boolean when Object is the target and the payload is a JSON bool" in {
      val converter = new ZioJsonPayloadConverter(new CodecRegistry())
      converter.fromData(rawPayload("true"), classOf[Object], classOf[Object]) shouldEqual
        java.lang.Boolean.TRUE
      converter.fromData(rawPayload("false"), classOf[Object], classOf[Object]) shouldEqual
        java.lang.Boolean.FALSE
    }

    "decode to null when Object is the target and the payload is JSON null" in {
      val converter = new ZioJsonPayloadConverter(new CodecRegistry())
      val decoded   = converter.fromData(rawPayload("null"), classOf[Object], classOf[Object])
      decoded shouldBe null
    }

    "decode to Vector when Object is the target and the payload is a JSON array" in {
      val converter = new ZioJsonPayloadConverter(new CodecRegistry())
      val decoded   = converter.fromData(rawPayload("[1,2,3]"), classOf[Object], classOf[Object])
      decoded shouldEqual Vector(
        java.lang.Integer.valueOf(1),
        java.lang.Integer.valueOf(2),
        java.lang.Integer.valueOf(3)
      )
    }

    "decode to Map when Object is the target and the payload is a JSON object" in {
      val converter = new ZioJsonPayloadConverter(new CodecRegistry())
      val decoded   = converter.fromData(rawPayload("""{"foo":1,"bar":"baz"}"""), classOf[Object], classOf[Object])
      decoded shouldEqual Map("foo" -> java.lang.Integer.valueOf(1), "bar" -> "baz")
    }

    "Object fallback defers to a user-registered Any / Object codec when present" in {
      // If a user explicitly registers a codec for `Object`/`Any`, the registry lookup wins and the
      // content-aware fallback is never consulted — users keep control of the type.
      val sentinel = "explicitly-registered-decoder"
      val registry = new CodecRegistry()
      val anyCodec = ZTemporalCodec.make[Any](
        zio.json.JsonEncoder.string.contramap(_.toString),
        zio.json.JsonDecoder.string.map(_ => sentinel)
      )
      registry.register(anyCodec)
      val converter = new ZioJsonPayloadConverter(registry)
      val decoded   = converter.fromData(rawPayload("\"whatever\""), classOf[Object], classOf[Object])
      decoded shouldEqual sentinel
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

    "container dispatch handles Either[String, User] — Left and Right" in {
      // Regression for the CI failure where `Right(value).getClass == scala.util.Right`, which didn't match
      // any registered `Either[L, R]` parent codec. Multiple distinct `Either` instantiations registered in the
      // same registry would all share the same raw class, so per-element dispatch is the only correct strategy.
      val registry = new CodecRegistry()
        .register(ZTemporalCodec[String])
        .register(ZTemporalCodec[User])
        .register(ZTemporalCodec[Either[String, User]])
      val converter = new ZioJsonPayloadConverter(registry)

      val right = converter.toData(Right(User(1, "a"))).orElseThrow(() => new AssertionError("right"))
      right.getData.toStringUtf8 shouldEqual """{"Right":{"id":1,"name":"a"}}"""

      val left = converter.toData(Left("boom")).orElseThrow(() => new AssertionError("left"))
      left.getData.toStringUtf8 shouldEqual """{"Left":"boom"}"""

      val decoded = converter.fromData(
        right,
        classOf[Either[String, User]].asInstanceOf[Class[Either[String, User]]],
        ZTemporalCodec[Either[String, User]].genericType
      )
      decoded shouldEqual Right(User(1, "a"))
    }

    "container dispatch for Either uses per-element runtime class, so two distinct Either shapes coexist" in {
      // With both `Either[String, Int]` and `Either[User, User]` registered, encoding a `Right(User(...))`
      // must use the `User` encoder, not pick an Either codec at random. Because the wire shape
      // `{"Right":<encoded value>}` is stable across all derived `Either` codecs, this round-trips regardless
      // of which `Either[L, R]` codec the decoder uses.
      val registry = new CodecRegistry()
        .register(ZTemporalCodec[Int])
        .register(ZTemporalCodec[String])
        .register(ZTemporalCodec[User])
        .register(ZTemporalCodec[Either[String, Int]])
        .register(ZTemporalCodec[Either[User, User]])
      val converter = new ZioJsonPayloadConverter(registry)

      val payload = converter.toData(Right(User(1, "a"))).orElseThrow(() => new AssertionError("right"))
      payload.getData.toStringUtf8 shouldEqual """{"Right":{"id":1,"name":"a"}}"""
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

  "ZioJsonPayloadConverter — sealed-trait parent only" should {

    // Regression for the parameterized-workflow path. A fixture like
    //   sealed trait Drink; object Drink { final case class Soda(kind: String) extends Drink }
    //   trait DrinkWorkflow[Input <: Drink] { @workflowMethod def serve(input: Input) }
    //   @workflowInterface trait SodaWorkflow extends DrinkWorkflow[Drink.Soda]
    // previously registered a flat `Soda` codec (encode side emits `{"kind":"cola"}`) but decoded via the
    // sealed-trait parent (decode side expects `{"Soda":{"kind":"cola"}}`). The macro now promotes the
    // subtype to its sealed parent so both sides agree.

    "encode Drink.Soda using the sealed-parent codec when only the parent is registered" in {
      val registry  = new CodecRegistry().register(ZTemporalCodec[Drink])
      val converter = new ZioJsonPayloadConverter(registry)

      val value: Drink.Soda = Drink.Soda("cola")
      val payload           = converter.toData(value).orElseThrow(() => new AssertionError("expected non-empty"))
      // Parent codec uses zio-json's default sealed-trait discriminator: `{"Soda":{"kind":"cola"}}`.
      payload.getData.toStringUtf8 shouldEqual """{"Soda":{"kind":"cola"}}"""
    }

    "decode a subtype payload via the parent codec when valueClass is the concrete subtype" in {
      val registry  = new CodecRegistry().register(ZTemporalCodec[Drink])
      val converter = new ZioJsonPayloadConverter(registry)

      val payload = converter
        .toData(Drink.Soda("cola"))
        .orElseThrow(() => new AssertionError("expected non-empty"))
      val decoded =
        converter.fromData(payload, classOf[Drink.Soda], classOf[Drink.Soda])
      decoded shouldEqual Drink.Soda("cola")
    }

    "decode via the parent codec when valueType is the parent class (Temporal upper-bound reflection case)" in {
      val registry  = new CodecRegistry().register(ZTemporalCodec[Drink])
      val converter = new ZioJsonPayloadConverter(registry)

      val payload = converter
        .toData(Drink.Soda("cola"))
        .orElseThrow(() => new AssertionError("expected non-empty"))
      val decoded =
        converter.fromData(payload, classOf[Drink], classOf[Drink])
      decoded shouldEqual Drink.Soda("cola")
    }

    "round-trip via parent even when the decode target is the concrete subtype class (covers hierarchy fallback)" in {
      // Encode: `v.getClass = Soda`, superclass walk finds parent → wrapped form.
      // Decode: `valueType = Soda`, direct `byType` lookup misses, `byClass` misses, `decoderForClassHierarchy`
      // walks the interface chain and finds `Drink`. The parent decoder produces the parent type, which the
      // unchecked cast inside `fromData` returns as-is.
      val registry  = new CodecRegistry().register(ZTemporalCodec[Drink])
      val converter = new ZioJsonPayloadConverter(registry)

      val payload = converter
        .toData(Drink.Soda("cola"))
        .orElseThrow(() => new AssertionError("expected non-empty"))
      payload.getData.toStringUtf8 shouldEqual """{"Soda":{"kind":"cola"}}"""

      val decoded: Drink = converter.fromData(payload, classOf[Drink.Soda], classOf[Drink.Soda])
      decoded shouldEqual Drink.Soda("cola")
    }
  }

  "CodecRegistry.decoderForClassHierarchy" should {

    "return a decoder registered on a sealed parent when queried with a concrete subtype class" in {
      val r = new CodecRegistry().register(ZTemporalCodec[Drink])
      r.decoderForClassHierarchy(classOf[Drink.Soda]) should not be null
      r.decoderForClassHierarchy(classOf[Drink.Juice]) should not be null
    }

    "return null when no ancestor is registered" in {
      val r = new CodecRegistry()
      r.decoderForClassHierarchy(classOf[Drink.Soda]) shouldBe null
    }
  }

  "ZioJsonPayloadConverter — user-defined generic case class (Triple-style)" should {

    // Regression for https://…/PR#203: a user-defined generic case class `Triple[A, B, C]` has no
    // container escape hatch on the encode path (it isn't an `Iterable`/`Option`/`Map`). The registry keeps
    // parameterized codecs out of `byClass` (see scaladoc there), so `encoderForClass` misses. This section
    // pins the `byRawClass` fallback behaviour that resolves this.

    import TripleFixture.Triple

    "round-trip when exactly one parameterized instantiation is registered" in {
      val registry = new CodecRegistry()
        .register(ZTemporalCodec[Int])
        .register(ZTemporalCodec[String])
        .register(ZTemporalCodec[Boolean])
        .register(ZTemporalCodec[Triple[Int, String, Boolean]])
      val converter = new ZioJsonPayloadConverter(registry)

      val value   = Triple(1, "x", true)
      val payload = converter.toData(value).orElseThrow(() => new AssertionError("expected non-empty"))
      payload.getData.toStringUtf8 shouldEqual """{"first":1,"second":"x","third":true}"""

      val tripleCodec = ZTemporalCodec[Triple[Int, String, Boolean]]
      val decoded     = converter.fromData(
        payload,
        classOf[Triple[Int, String, Boolean]].asInstanceOf[Class[Triple[Int, String, Boolean]]],
        tripleCodec.genericType
      )
      decoded shouldEqual value
    }

    "fail encode with a clear ambiguity message when two parameterized instantiations share a raw class" in {
      val registry = new CodecRegistry()
        .register(ZTemporalCodec[Int])
        .register(ZTemporalCodec[Long])
        .register(ZTemporalCodec[String])
        .register(ZTemporalCodec[Boolean])
        .register(ZTemporalCodec[Triple[Int, String, Boolean]])
        .register(ZTemporalCodec[Triple[Long, String, Boolean]])
      val converter = new ZioJsonPayloadConverter(registry)

      val thrown = the[DataConverterException] thrownBy converter.toData(Triple(1, "x", true))
      thrown.getMessage should include("Ambiguous ZTemporalCodec")
      thrown.getMessage should include(classOf[Triple[_, _, _]].getName)
      // Both candidate type names should surface so the user knows which to restructure.
      // ClassTag-based `genericType` reports Scala primitive classes as their erased primitive counterparts
      // (`int`, `long`), so we assert on those.
      thrown.getMessage should (include("int") and include("long"))
    }

    "decode path still dispatches on full generic Type (unaffected by the fallback)" in {
      val registry = new CodecRegistry()
        .register(ZTemporalCodec[Int])
        .register(ZTemporalCodec[Long])
        .register(ZTemporalCodec[String])
        .register(ZTemporalCodec[Boolean])
        .register(ZTemporalCodec[Triple[Int, String, Boolean]])
        .register(ZTemporalCodec[Triple[Long, String, Boolean]])
      val converter = new ZioJsonPayloadConverter(registry)

      val payload = Payload
        .newBuilder()
        .putMetadata("encoding", ByteString.copyFromUtf8("json/zio"))
        .setData(ByteString.copyFromUtf8("""{"first":7,"second":"x","third":true}"""))
        .build()

      val codec        = ZTemporalCodec[Triple[Long, String, Boolean]]
      val decodedLongs = converter.fromData(
        payload,
        classOf[Triple[Long, String, Boolean]].asInstanceOf[Class[Triple[Long, String, Boolean]]],
        codec.genericType
      )
      decodedLongs shouldEqual Triple(7L, "x", true)
    }

    "container dispatch still fires for List even when raw-class fallback exists for another generic" in {
      // Container escape hatch must be consulted BEFORE the raw-class fallback. If we reversed the order,
      // registering a `Triple` wouldn't affect lists — but registering a `List[A]` would be matched via the
      // `byRawClass` path too and bypass per-element dispatch. This test pins the ordering.
      val registry = new CodecRegistry()
        .register(ZTemporalCodec[Int])
        .register(ZTemporalCodec[String])
        .register(ZTemporalCodec[Boolean])
        .register(ZTemporalCodec[Triple[Int, String, Boolean]])
      val converter = new ZioJsonPayloadConverter(registry)

      val users   = List(1, 2, 3)
      val payload = converter.toData(users).orElseThrow(() => new AssertionError("list of ints"))
      payload.getData.toStringUtf8 shouldEqual "[1,2,3]"
    }
  }
}

object TripleFixture {
  final case class Triple[A, B, C](first: A, second: B, third: C)
  object Triple {
    given [A: JsonCodec, B: JsonCodec, C: JsonCodec]: JsonCodec[Triple[A, B, C]] =
      DeriveJsonCodec.gen[Triple[A, B, C]]
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

  sealed trait Drink derives JsonCodec
  object Drink {
    final case class Soda(kind: String)  extends Drink derives JsonCodec
    final case class Juice(kind: String) extends Drink derives JsonCodec
  }

  private def rawPayload(json: String): Payload =
    Payload
      .newBuilder()
      .putMetadata("encoding", ByteString.copyFromUtf8("json/zio"))
      .setData(ByteString.copyFrom(json, StandardCharsets.UTF_8))
      .build()
}
