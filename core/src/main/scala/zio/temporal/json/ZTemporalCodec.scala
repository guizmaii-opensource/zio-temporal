package zio.temporal.json

import zio.json.{JsonDecoder, JsonEncoder}

import java.lang.reflect.{ParameterizedType, Type}
import scala.annotation.implicitNotFound
import scala.reflect.ClassTag

/** Evidence that a value of type `A` can be serialized to / deserialized from JSON for crossing a Temporal boundary
  * (workflow/activity/signal/query arguments and results).
  *
  * The presence of a `ZTemporalCodec[A]` is enforced at compile time by zio-temporal's macros wherever a Temporal
  * interaction is performed. This replaces the previous Jackson-based runtime reflection, which silently emitted
  * malformed JSON when Scala-aware modules were not registered.
  *
  * Provide a `ZTemporalCodec[A]` by placing implicit `JsonEncoder[A]` and `JsonDecoder[A]` (or a `JsonCodec[A]`) in
  * `A`'s companion object. For case classes and sealed traits, the simplest form is
  *
  * {{{
  *   final case class Foo(x: Int, y: String)
  *   object Foo {
  *     implicit val codec: ZTemporalCodec[Foo] = ZTemporalCodec.derived
  *   }
  * }}}
  *
  * which derives zio-json encoder/decoder and wraps them in a `ZTemporalCodec`.
  *
  * The typeclass carries (in addition to the encoder and decoder) a [[java.lang.Class]] and a
  * [[java.lang.reflect.Type]], so the underlying Temporal Java SDK can dispatch on generic types such as `List[Foo]`
  * vs. `List[Bar]`.
  */
@implicitNotFound(
  "\n" +
    "No ZTemporalCodec[${A}] in scope — Temporal needs a zio-json codec to (de)serialize ${A}\n" +
    "across workflow/activity/signal/query boundaries.\n" +
    "\n" +
    "Provide one, e.g. on ${A}'s companion object:\n" +
    "\n" +
    "    object ${A} {\n" +
    "      implicit val codec: ZTemporalCodec[${A}] = ZTemporalCodec.derived\n" +
    "    }\n" +
    "\n" +
    "For a type you do not control, place the implicit somewhere that gets imported at the call site.\n"
)
trait ZTemporalCodec[A] {

  /** Encoder used to serialize values of `A` to JSON. */
  def encoder: JsonEncoder[A]

  /** Decoder used to deserialize values of `A` from JSON. */
  def decoder: JsonDecoder[A]

  /** Runtime class used to index the registry on the encode path (Temporal hands us only `v.getClass`) and as the
    * value-class argument to Temporal's `getResultAsync`/`query`/`execute` APIs.
    */
  def klass: Class[A]

  /** Fully-qualified generic type used to index the registry on the decode path. For ground types this equals `klass`.
    * For generic types it is a `ParameterizedType` carrying the type-argument tree.
    */
  def genericType: Type
}

object ZTemporalCodec extends LowPriorityZTemporalCodecInstances0 with LowPriorityZTemporalCodecInstances1 {

  /** Summon an existing instance. */
  def apply[A](implicit ev: ZTemporalCodec[A]): ev.type = ev

  /** Build a codec explicitly from the raw zio-json pieces. */
  def make[A](jsonEncoder: JsonEncoder[A], jsonDecoder: JsonDecoder[A])(implicit ct: ClassTag[A]): ZTemporalCodec[A] =
    new Kind0[A](jsonEncoder, jsonDecoder)

  /** Derive a codec for a case class, case object, or sealed trait by generating a zio-json codec via
    * `zio.json.JsonCodec.derived` and wrapping it. Requires a `deriving.Mirror.Of[A]`, as all zio-json Scala 3
    * derivation does.
    */
  inline def derived[A](
    using m: scala.deriving.Mirror.Of[A],
    ct:      ClassTag[A],
    cfg:     zio.json.JsonCodecConfiguration = zio.json.JsonCodecConfiguration.default
  ): ZTemporalCodec[A] = {
    given zio.json.JsonCodecConfiguration = cfg
    val jc                                = zio.json.JsonCodec.derived[A]
    new Kind0[A](jc.encoder, jc.decoder)
  }

  /** Codec for `Unit`. zio-json does not ship one — `Unit` is a Scala-only concept — and Temporal uses it everywhere
    * (activities returning unit, signals with no payload, etc.). Serialized as an empty JSON object `{}`. Accepts any
    * JSON on decode, matching the behaviour of the old Jackson-based `BoxedUnitModule`.
    */
  implicit val unitCodec: ZTemporalCodec[Unit] = {
    val encoder: JsonEncoder[Unit]  = JsonEncoder[zio.json.ast.Json].contramap(_ => zio.json.ast.Json.Obj())
    val decoder: JsonDecoder[Unit]  = JsonDecoder[zio.json.ast.Json].map(_ => ())
    implicit val ct: ClassTag[Unit] = ClassTag.Unit
    new Kind0[Unit](encoder, decoder)
  }

  /** Concrete implementation of a `ZTemporalCodec[A]` with equality based on rawType + type arguments.
    *
    * We avoid leaking anonymous `ParameterizedType` subclasses into the registry; those don't implement `equals` and
    * make map lookups fail.
    */
  private[json] final class Kind0[A](
    val encoder: JsonEncoder[A],
    val decoder: JsonDecoder[A]
  )(implicit ct: ClassTag[A])
      extends ZTemporalCodec[A] {
    val klass: Class[A]   = ct.runtimeClass.asInstanceOf[Class[A]]
    val genericType: Type = ct.runtimeClass
  }

  private[json] final class KindN[A](
    val encoder:     JsonEncoder[A],
    val decoder:     JsonDecoder[A],
    val klass:       Class[A],
    val genericType: Type)
      extends ZTemporalCodec[A]

  /** A `java.lang.reflect.ParameterizedType` that implements `equals`/`hashCode` structurally. */
  private[json] final class ZParameterizedType(rawType: Class[_], typeArgs: Array[Type]) extends ParameterizedType {
    override def getActualTypeArguments: Array[Type] = typeArgs
    override def getRawType: Type                    = rawType
    override def getOwnerType: Type                  = null

    override def equals(other: Any): Boolean = other match {
      case that: ParameterizedType =>
        that.getOwnerType == null &&
        rawType == that.getRawType &&
        java.util.Arrays.equals(
          typeArgs.asInstanceOf[Array[AnyRef]],
          that.getActualTypeArguments.asInstanceOf[Array[AnyRef]]
        )
      case _ => false
    }

    override def hashCode: Int =
      java.util.Arrays.hashCode(typeArgs.asInstanceOf[Array[AnyRef]]) ^ rawType.hashCode()

    override def toString: String =
      s"${rawType.getTypeName}<${typeArgs.map(_.getTypeName).mkString(", ")}>"
  }
}

/** Kind-1 through kind-7 derivations for generic types. Higher priority than the ground `kind0` so that the richer
  * `ParameterizedType` info is used whenever type parameters are available.
  */
private[json] trait LowPriorityZTemporalCodecInstances0 {
  import ZTemporalCodec.{KindN, ZParameterizedType}

  implicit def kind1[F[_], A](
    implicit inner: ZTemporalCodec[A],
    enc:            JsonEncoder[F[A]],
    dec:            JsonDecoder[F[A]],
    ct:             ClassTag[F[A]]
  ): ZTemporalCodec[F[A]] =
    new KindN[F[A]](
      enc,
      dec,
      ct.runtimeClass.asInstanceOf[Class[F[A]]],
      new ZParameterizedType(ct.runtimeClass, Array(inner.genericType))
    )

  implicit def kind2[F[_, _], A, B](
    implicit innerA: ZTemporalCodec[A],
    innerB:          ZTemporalCodec[B],
    enc:             JsonEncoder[F[A, B]],
    dec:             JsonDecoder[F[A, B]],
    ct:              ClassTag[F[A, B]]
  ): ZTemporalCodec[F[A, B]] =
    new KindN[F[A, B]](
      enc,
      dec,
      ct.runtimeClass.asInstanceOf[Class[F[A, B]]],
      new ZParameterizedType(ct.runtimeClass, Array(innerA.genericType, innerB.genericType))
    )

  implicit def kind3[F[_, _, _], A, B, C](
    implicit innerA: ZTemporalCodec[A],
    innerB:          ZTemporalCodec[B],
    innerC:          ZTemporalCodec[C],
    enc:             JsonEncoder[F[A, B, C]],
    dec:             JsonDecoder[F[A, B, C]],
    ct:              ClassTag[F[A, B, C]]
  ): ZTemporalCodec[F[A, B, C]] =
    new KindN[F[A, B, C]](
      enc,
      dec,
      ct.runtimeClass.asInstanceOf[Class[F[A, B, C]]],
      new ZParameterizedType(
        ct.runtimeClass,
        Array(innerA.genericType, innerB.genericType, innerC.genericType)
      )
    )

  implicit def kind4[F[_, _, _, _], A, B, C, D](
    implicit innerA: ZTemporalCodec[A],
    innerB:          ZTemporalCodec[B],
    innerC:          ZTemporalCodec[C],
    innerD:          ZTemporalCodec[D],
    enc:             JsonEncoder[F[A, B, C, D]],
    dec:             JsonDecoder[F[A, B, C, D]],
    ct:              ClassTag[F[A, B, C, D]]
  ): ZTemporalCodec[F[A, B, C, D]] =
    new KindN[F[A, B, C, D]](
      enc,
      dec,
      ct.runtimeClass.asInstanceOf[Class[F[A, B, C, D]]],
      new ZParameterizedType(
        ct.runtimeClass,
        Array(innerA.genericType, innerB.genericType, innerC.genericType, innerD.genericType)
      )
    )

  implicit def kind5[F[_, _, _, _, _], A, B, C, D, E](
    implicit innerA: ZTemporalCodec[A],
    innerB:          ZTemporalCodec[B],
    innerC:          ZTemporalCodec[C],
    innerD:          ZTemporalCodec[D],
    innerE:          ZTemporalCodec[E],
    enc:             JsonEncoder[F[A, B, C, D, E]],
    dec:             JsonDecoder[F[A, B, C, D, E]],
    ct:              ClassTag[F[A, B, C, D, E]]
  ): ZTemporalCodec[F[A, B, C, D, E]] =
    new KindN[F[A, B, C, D, E]](
      enc,
      dec,
      ct.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E]]],
      new ZParameterizedType(
        ct.runtimeClass,
        Array(innerA.genericType, innerB.genericType, innerC.genericType, innerD.genericType, innerE.genericType)
      )
    )

  implicit def kind6[F[_, _, _, _, _, _], A, B, C, D, E, G](
    implicit innerA: ZTemporalCodec[A],
    innerB:          ZTemporalCodec[B],
    innerC:          ZTemporalCodec[C],
    innerD:          ZTemporalCodec[D],
    innerE:          ZTemporalCodec[E],
    innerG:          ZTemporalCodec[G],
    enc:             JsonEncoder[F[A, B, C, D, E, G]],
    dec:             JsonDecoder[F[A, B, C, D, E, G]],
    ct:              ClassTag[F[A, B, C, D, E, G]]
  ): ZTemporalCodec[F[A, B, C, D, E, G]] =
    new KindN[F[A, B, C, D, E, G]](
      enc,
      dec,
      ct.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E, G]]],
      new ZParameterizedType(
        ct.runtimeClass,
        Array(
          innerA.genericType,
          innerB.genericType,
          innerC.genericType,
          innerD.genericType,
          innerE.genericType,
          innerG.genericType
        )
      )
    )

  implicit def kind7[F[_, _, _, _, _, _, _], A, B, C, D, E, G, H](
    implicit innerA: ZTemporalCodec[A],
    innerB:          ZTemporalCodec[B],
    innerC:          ZTemporalCodec[C],
    innerD:          ZTemporalCodec[D],
    innerE:          ZTemporalCodec[E],
    innerG:          ZTemporalCodec[G],
    innerH:          ZTemporalCodec[H],
    enc:             JsonEncoder[F[A, B, C, D, E, G, H]],
    dec:             JsonDecoder[F[A, B, C, D, E, G, H]],
    ct:              ClassTag[F[A, B, C, D, E, G, H]]
  ): ZTemporalCodec[F[A, B, C, D, E, G, H]] =
    new KindN[F[A, B, C, D, E, G, H]](
      enc,
      dec,
      ct.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E, G, H]]],
      new ZParameterizedType(
        ct.runtimeClass,
        Array(
          innerA.genericType,
          innerB.genericType,
          innerC.genericType,
          innerD.genericType,
          innerE.genericType,
          innerG.genericType,
          innerH.genericType
        )
      )
    )
}

/** Ground-kind fallback. Lower priority than the generic derivations so that parameterized types get precise
  * `ParameterizedType` keys instead of the raw class.
  */
private[json] trait LowPriorityZTemporalCodecInstances1 {
  implicit def kind0[A](
    implicit enc: JsonEncoder[A],
    dec:          JsonDecoder[A],
    ct:           ClassTag[A]
  ): ZTemporalCodec[A] =
    new ZTemporalCodec.Kind0[A](enc, dec)
}
