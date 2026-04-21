package zio.temporal.json

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}

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
  }
}

// Fixture types

final case class Foo(x: Int, y: String)

object Foo {
  given JsonEncoder[Foo] = DeriveJsonEncoder.gen[Foo]
  given JsonDecoder[Foo] = DeriveJsonDecoder.gen[Foo]
}

final case class Bar(z: Boolean)

object Bar {
  given JsonEncoder[Bar] = DeriveJsonEncoder.gen[Bar]
  given JsonDecoder[Bar] = DeriveJsonDecoder.gen[Bar]
}

sealed trait Shape
object Shape {
  final case class Rectangle(w: Double, h: Double) extends Shape
  final case class Circle(r: Double)               extends Shape

  given JsonEncoder[Shape] = DeriveJsonEncoder.gen[Shape]
  given JsonDecoder[Shape] = DeriveJsonDecoder.gen[Shape]
}
