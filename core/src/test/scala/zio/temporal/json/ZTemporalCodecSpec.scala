package zio.temporal.json

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import zio.json.{
  DeriveJsonDecoder,
  DeriveJsonEncoder,
  JsonCodec,
  JsonDecoder,
  JsonEncoder,
  jsonDiscriminator,
  jsonField,
  jsonHint
}

import java.time.Instant
import java.util.UUID

class ZTemporalCodecSpec extends AnyWordSpec with Matchers {

  "ZTemporalCodec" should {

    "auto-derive a codec for primitives via kind0" in {
      val c = ZTemporalCodec[Int]
      c.klass shouldEqual classOf[Int]
      c.genericType shouldEqual classOf[Int]
      c.encoder.encodeJson(42, None).toString shouldEqual "42"
      c.decoder.decodeJson("42") shouldEqual Right(42)
    }

    "auto-derive a codec for String" in {
      val c = ZTemporalCodec[String]
      c.encoder.encodeJson("hi", None).toString shouldEqual "\"hi\""
      c.decoder.decodeJson("\"hi\"") shouldEqual Right("hi")
    }

    "auto-derive for UUID via kind0" in {
      val c  = ZTemporalCodec[UUID]
      val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
      c.encoder.encodeJson(id, None).toString shouldEqual "\"00000000-0000-0000-0000-000000000001\""
      c.decoder.decodeJson("\"00000000-0000-0000-0000-000000000001\"") shouldEqual Right(id)
    }

    "auto-derive for Instant via kind0" in {
      val c = ZTemporalCodec[Instant]
      val t = Instant.parse("2026-04-21T12:00:00Z")
      c.decoder.decodeJson(c.encoder.encodeJson(t, None)) shouldEqual Right(t)
    }

    "encode Unit as {} and decode any JSON to ()" in {
      val c = ZTemporalCodec[Unit]
      c.encoder.encodeJson((), None).toString shouldEqual "{}"
      c.decoder.decodeJson("{}") shouldEqual Right(())
      c.decoder.decodeJson("42") shouldEqual Right(()) // accepts any JSON
    }

    "auto-derives a codec for a case class from separate encoder+decoder" in {
      val c = ZTemporalCodec[Foo]
      val v = Foo(x = 7, y = "seven")
      c.decoder.decodeJson(c.encoder.encodeJson(v, None)) shouldEqual Right(v)
    }

    "kind1 produces a ParameterizedType for List[A]" in {
      val c = ZTemporalCodec[List[Foo]]
      c.klass shouldEqual classOf[List[_]]
      c.genericType match {
        case pt: java.lang.reflect.ParameterizedType =>
          pt.getRawType shouldEqual classOf[List[_]]
          pt.getActualTypeArguments.toList shouldEqual List(classOf[Foo])
        case other => fail(s"Expected ParameterizedType, got $other")
      }
    }

    "ParameterizedType instances compare equal structurally" in {
      val a = ZTemporalCodec[List[Foo]].genericType
      val b = ZTemporalCodec[List[Foo]].genericType
      a shouldEqual b
      a.hashCode shouldEqual b.hashCode
      // and different for different arg
      a should not equal ZTemporalCodec[List[Bar]].genericType
    }

    "roundtrips an Option[Foo]" in {
      val c = ZTemporalCodec[Option[Foo]]
      c.decoder.decodeJson(c.encoder.encodeJson(Some(Foo(1, "a")), None)) shouldEqual Right(Some(Foo(1, "a")))
      c.decoder.decodeJson(c.encoder.encodeJson(None: Option[Foo], None)) shouldEqual Right(None)
    }

    "roundtrips a sealed trait" in {
      val c        = ZTemporalCodec[Shape]
      val r: Shape = Shape.Rectangle(2.0, 3.0)
      val k: Shape = Shape.Circle(5.0)
      c.decoder.decodeJson(c.encoder.encodeJson(r, None)) shouldEqual Right(r)
      c.decoder.decodeJson(c.encoder.encodeJson(k, None)) shouldEqual Right(k)
    }

    "`derives ZTemporalCodec` on a case class works end-to-end" in {
      val c = ZTemporalCodec[DerivedType]
      val v = DerivedType(1, "alice")
      c.encoder.encodeJson(v, None).toString shouldEqual """{"id":1,"name":"alice"}"""
      c.decoder.decodeJson(c.encoder.encodeJson(v, None)) shouldEqual Right(v)
    }

    "`derives ZTemporalCodec` on a sealed trait works end-to-end" in {
      val c               = ZTemporalCodec[DerivedShape]
      val a: DerivedShape = DerivedShape.A(42)
      val b: DerivedShape = DerivedShape.B("hi")
      c.decoder.decodeJson(c.encoder.encodeJson(a, None)) shouldEqual Right(a)
      c.decoder.decodeJson(c.encoder.encodeJson(b, None)) shouldEqual Right(b)
    }

    "`derives ZTemporalCodec` on a Scala 3 enum with data cases works end-to-end" in {
      val c              = ZTemporalCodec[DerivedEnum]
      val a: DerivedEnum = DerivedEnum.A(42)
      val b: DerivedEnum = DerivedEnum.B("hi")
      c.decoder.decodeJson(c.encoder.encodeJson(a, None)) shouldEqual Right(a)
      c.decoder.decodeJson(c.encoder.encodeJson(b, None)) shouldEqual Right(b)
    }

    "respects zio-json field-rename annotations (`@jsonField`) in a derived codec" in {
      val c = ZTemporalCodec[AnnotatedUser]
      val v = AnnotatedUser(id = 42, name = "alice")
      // `@jsonField("user_id")` must rename the field on the wire.
      c.encoder.encodeJson(v, None).toString shouldEqual """{"user_id":42,"name":"alice"}"""
      c.decoder.decodeJson("""{"user_id":42,"name":"alice"}""") shouldEqual Right(v)
    }

    "respects zio-json subtype-rename annotations (`@jsonHint`) in a sealed-trait derived codec" in {
      val c                       = ZTemporalCodec[AnnotatedEvent]
      val created: AnnotatedEvent = AnnotatedEvent.Created(42)
      // `@jsonHint("CREATED")` must rename the outer discriminator key.
      c.encoder.encodeJson(created, None).toString shouldEqual """{"CREATED":{"id":42}}"""
      c.decoder.decodeJson("""{"CREATED":{"id":42}}""") shouldEqual Right(created)
    }

    "respects zio-json `@jsonDiscriminator(\"type\")` to use an internal discriminator field" in {
      val c                           = ZTemporalCodec[DiscriminatedEvent]
      val created: DiscriminatedEvent = DiscriminatedEvent.Created(42)
      val deleted: DiscriminatedEvent = DiscriminatedEvent.Deleted(7)
      // With `@jsonDiscriminator("type")`, subtypes encode with a `"type"` field inside the object rather than
      // as an outer `{"Subtype":{...}}` wrapper — matching the shape the old Jackson integration used.
      c.encoder.encodeJson(created, None).toString shouldEqual """{"type":"Created","id":42}"""
      c.encoder.encodeJson(deleted, None).toString shouldEqual """{"type":"Deleted","id":7}"""
      c.decoder.decodeJson("""{"type":"Created","id":42}""") shouldEqual Right(created)
      c.decoder.decodeJson("""{"type":"Deleted","id":7}""") shouldEqual Right(deleted)
    }
  }
}

// Fixture types — prove `derives JsonCodec` is enough end-to-end.

final case class Foo(x: Int, y: String) derives JsonCodec

final case class Bar(z: Boolean) derives JsonCodec

sealed trait Shape derives JsonCodec
object Shape {
  final case class Rectangle(w: Double, h: Double) extends Shape
  final case class Circle(r: Double)               extends Shape
}

// Derives ZTemporalCodec directly — should work via the inline `derived` in the companion.
final case class DerivedType(id: Int, name: String) derives ZTemporalCodec

sealed trait DerivedShape derives ZTemporalCodec
object DerivedShape {
  final case class A(n: Int)    extends DerivedShape
  final case class B(s: String) extends DerivedShape
}

enum DerivedEnum derives ZTemporalCodec {
  case A(n: Int)
  case B(s: String)
}

final case class AnnotatedUser(@jsonField("user_id") id: Int, name: String) derives ZTemporalCodec

sealed trait AnnotatedEvent derives ZTemporalCodec
object AnnotatedEvent {
  @jsonHint("CREATED") final case class Created(id: Int) extends AnnotatedEvent
  @jsonHint("DELETED") final case class Deleted(id: Int) extends AnnotatedEvent
}

@jsonDiscriminator("type")
sealed trait DiscriminatedEvent derives ZTemporalCodec
object DiscriminatedEvent {
  final case class Created(id: Int) extends DiscriminatedEvent
  final case class Deleted(id: Int) extends DiscriminatedEvent
}
