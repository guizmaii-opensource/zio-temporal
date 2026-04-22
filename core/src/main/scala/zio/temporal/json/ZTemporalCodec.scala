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
  * Provide a `ZTemporalCodec[A]` by placing a given `JsonEncoder[A]` + `JsonDecoder[A]` (or a `JsonCodec[A]`) in `A`'s
  * companion object. For case classes and sealed traits, the simplest forms are
  *
  * {{{
  *   final case class Foo(x: Int, y: String) derives ZTemporalCodec
  *
  *   // or, if you'd rather derive zio-json directly and let the bridges pick it up:
  *   final case class Foo(x: Int, y: String) derives JsonCodec
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
    "The simplest fix for a case class or sealed trait is to derive it:\n" +
    "\n" +
    "    final case class ${A}(...) derives ZTemporalCodec\n" +
    "\n" +
    "or, if you prefer to derive the zio-json codec directly:\n" +
    "\n" +
    "    final case class ${A}(...) derives JsonCodec\n" +
    "\n" +
    "For a type you do not own, place a `given ZTemporalCodec[${A}]` somewhere imported at the call site.\n"
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
  def apply[A](using ev: ZTemporalCodec[A]): ev.type = ev

  /** Build a codec explicitly from the raw zio-json pieces. */
  def make[A](jsonEncoder: JsonEncoder[A], jsonDecoder: JsonDecoder[A])(using ClassTag[A]): ZTemporalCodec[A] =
    new Kind0[A](jsonEncoder, jsonDecoder)

  /** Derive a codec for a case class, case object, or sealed trait by generating a zio-json codec via
    * `zio.json.JsonCodec.derived` and wrapping it. Requires a `deriving.Mirror.Of[A]`, as all zio-json Scala 3
    * derivation does.
    */
  inline def derived[A](
    using m:  scala.deriving.Mirror.Of[A],
    classTag: ClassTag[A],
    cfg:      zio.json.JsonCodecConfiguration = zio.json.JsonCodecConfiguration.default
  ): ZTemporalCodec[A] = {
    given zio.json.JsonCodecConfiguration = cfg
    val jsonCodec                         = zio.json.JsonCodec.derived[A]
    new Kind0[A](jsonCodec.encoder, jsonCodec.decoder)
  }

  /** Codec for `Unit`. zio-json does not ship one — `Unit` is a Scala-only concept — and Temporal uses it everywhere
    * (activities returning unit, signals with no payload, etc.). Serialized as an empty JSON object `{}`. Accepts any
    * JSON on decode, matching the behaviour of the old Jackson-based `BoxedUnitModule`.
    *
    * Implemented directly against zio-json's `Write` and `RetractReader` rather than going through
    * `JsonEncoder[Json].contramap` / `JsonDecoder[Json].map`, so:
    *   - encode writes the two characters `{` `}` straight to the output — no intermediate `Json.Obj` allocation.
    *   - decode calls `Lexer.skipValue` to drain whatever JSON is in the stream — no intermediate AST allocation.
    *   - `toJsonAST` returns [[zio.json.ast.Json.Obj.empty]], a constant singleton.
    */
  // Cache the `Right(emptyObj)` wrapper too — `toJsonAST` is on the hot path and `Right.apply` allocates otherwise.
  private val unitToJsonASTResult: Either[String, zio.json.ast.Json] = Right(zio.json.ast.Json.Obj.empty)

  given unitEncoder: JsonEncoder[Unit] =
    new JsonEncoder.AbstractJsonEncoder[Unit] {
      override def unsafeEncode(a: Unit, indent: Option[Int], out: zio.json.internal.Write): Unit =
        out.write('{', '}')

      override def toJsonAST(a: Unit): Either[String, zio.json.ast.Json] =
        unitToJsonASTResult
    }

  given unitDecoder: JsonDecoder[Unit] =
    new JsonDecoder.AbstractJsonDecoder[Unit] {
      override def unsafeDecode(trace: List[zio.json.JsonError], in: zio.json.internal.RetractReader): Unit =
        // Drain whatever JSON is in the stream; a Unit decoder is tolerant of any payload shape.
        // `Lexer.skipValue` → `skipNumber` throws `UnexpectedEnd` when a bare number runs to EOF
        // (e.g. the input `"42"`): `skipNumber` keeps reading digits until `readChar` hits EOF. For
        // Unit semantics "EOF reached" is the same as "nothing more to consume" — both decode to ().
        try zio.json.internal.Lexer.skipValue(trace, in)
        catch {
          case _: zio.json.internal.UnexpectedEnd => ()
        }

      override def unsafeFromJsonAST(trace: List[zio.json.JsonError], json: zio.json.ast.Json): Unit = ()
    }

  given unitCodec: ZTemporalCodec[Unit] = {
    given classTag: ClassTag[Unit] = ClassTag.Unit
    new Kind0[Unit](unitEncoder, unitDecoder)
  }

  /** Bridge: when a `JsonCodec[A]` is in scope (e.g. from `final case class Foo(...) derives JsonCodec`), expose its
    * encoder as a `JsonEncoder[A]`. Lets zio-json's generic combinators (`JsonEncoder.list`, `JsonEncoder.option`, …)
    * find what they need without forcing users to hand-write a separate `given JsonEncoder[Foo] = jsonCodec.encoder`.
    *
    * Intentionally generic so it stays less-specific than zio-json's own `JsonEncoder.int` etc. — Scala's
    * given-specificity rules prefer the non-generic instance for primitives, avoiding ambiguity.
    */
  given jsonEncoderFromJsonCodec[A](using jsonCodec: zio.json.JsonCodec[A]): JsonEncoder[A] = jsonCodec.encoder

  /** Bridge counterpart to [[jsonEncoderFromJsonCodec]]. */
  given jsonDecoderFromJsonCodec[A](using jsonCodec: zio.json.JsonCodec[A]): JsonDecoder[A] = jsonCodec.decoder

  /** Concrete implementation of a `ZTemporalCodec[A]` with equality based on rawType + type arguments.
    *
    * We avoid leaking anonymous `ParameterizedType` subclasses into the registry; those don't implement `equals` and
    * make map lookups fail.
    */
  private[json] final class Kind0[A](
    val encoder: JsonEncoder[A],
    val decoder: JsonDecoder[A]
  )(using classTag: ClassTag[A])
      extends ZTemporalCodec[A] {
    private val runtime = classTag.runtimeClass
    // Prevent the `make[List[Foo]]` / `derived[List[Foo]]` footgun: a parameterized type is indistinguishable
    // from its raw class under `classTag.runtimeClass` (Scala erases type arguments at that boundary). If we
    // accepted such a codec, `CodecRegistry.register` would index it under `byClass[List]` and the last-registered
    // `List[X]` would silently overwrite every other instantiation.
    require(
      runtime.getTypeParameters.length == 0,
      s"ZTemporalCodec.Kind0 cannot hold a parameterized type: `${runtime.getName}` has " +
        s"${runtime.getTypeParameters.length} type parameter(s). Keying a parameterized type on its raw class " +
        s"would let the last-registered `${runtime.getSimpleName}[X]` silently overwrite every other " +
        "instantiation. Use a kindN given (`kind1[F[_], A]`, `kind2[F[_,_], A, B]`, …) so the full " +
        "`ParameterizedType` is retained."
    )
    val klass: Class[A]   = runtime.asInstanceOf[Class[A]]
    val genericType: Type = runtime
  }

  private[json] final class KindN[A](
    val encoder:     JsonEncoder[A],
    val decoder:     JsonDecoder[A],
    val klass:       Class[A],
    val genericType: Type)
      extends ZTemporalCodec[A]

  /** A `java.lang.reflect.ParameterizedType` that implements `equals`/`hashCode` structurally. */
  private[json] final class ZParameterizedType(rawType: Class[_], typeArgs: Array[Type]) extends ParameterizedType {
    require(
      typeArgs.length == rawType.getTypeParameters.length,
      s"ZParameterizedType arity mismatch: `${rawType.getName}` declares ${rawType.getTypeParameters.length} " +
        s"type parameter(s), but ${typeArgs.length} type argument(s) were supplied."
    )
    override def getActualTypeArguments: Array[Type] = typeArgs
    override def getRawType: Type                    = rawType
    override def getOwnerType: Type                  = null

    override def equals(other: Any): Boolean =
      other match {
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
      s"${rawType.getTypeName}[${typeArgs.map(_.getTypeName).mkString(", ")}]"
  }
}

/** Kind-1 through kind-22 derivations for generic types. Higher priority than the ground `kind0` so that the richer
  * `ParameterizedType` info is used whenever type parameters are available.
  */
private[json] trait LowPriorityZTemporalCodecInstances0 {
  import ZTemporalCodec.{KindN, ZParameterizedType}

  given kind1[F[_], A](
    using inner: ZTemporalCodec[A],
    encoder:     JsonEncoder[F[A]],
    decoder:     JsonDecoder[F[A]],
    classTag:    ClassTag[F[A]]
  ): ZTemporalCodec[F[A]] =
    new KindN[F[A]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A]]],
      new ZParameterizedType(classTag.runtimeClass, Array(inner.genericType))
    )

  given kind2[F[_, _], A, B](
    using innerA: ZTemporalCodec[A],
    innerB:       ZTemporalCodec[B],
    encoder:      JsonEncoder[F[A, B]],
    decoder:      JsonDecoder[F[A, B]],
    classTag:     ClassTag[F[A, B]]
  ): ZTemporalCodec[F[A, B]] =
    new KindN[F[A, B]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B]]],
      new ZParameterizedType(classTag.runtimeClass, Array(innerA.genericType, innerB.genericType))
    )

  given kind3[F[_, _, _], A, B, C](
    using innerA: ZTemporalCodec[A],
    innerB:       ZTemporalCodec[B],
    innerC:       ZTemporalCodec[C],
    encoder:      JsonEncoder[F[A, B, C]],
    decoder:      JsonDecoder[F[A, B, C]],
    classTag:     ClassTag[F[A, B, C]]
  ): ZTemporalCodec[F[A, B, C]] =
    new KindN[F[A, B, C]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C]]],
      new ZParameterizedType(
        classTag.runtimeClass,
        Array(innerA.genericType, innerB.genericType, innerC.genericType)
      )
    )

  given kind4[F[_, _, _, _], A, B, C, D](
    using innerA: ZTemporalCodec[A],
    innerB:       ZTemporalCodec[B],
    innerC:       ZTemporalCodec[C],
    innerD:       ZTemporalCodec[D],
    encoder:      JsonEncoder[F[A, B, C, D]],
    decoder:      JsonDecoder[F[A, B, C, D]],
    classTag:     ClassTag[F[A, B, C, D]]
  ): ZTemporalCodec[F[A, B, C, D]] =
    new KindN[F[A, B, C, D]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C, D]]],
      new ZParameterizedType(
        classTag.runtimeClass,
        Array(innerA.genericType, innerB.genericType, innerC.genericType, innerD.genericType)
      )
    )

  given kind5[F[_, _, _, _, _], A, B, C, D, E](
    using innerA: ZTemporalCodec[A],
    innerB:       ZTemporalCodec[B],
    innerC:       ZTemporalCodec[C],
    innerD:       ZTemporalCodec[D],
    innerE:       ZTemporalCodec[E],
    encoder:      JsonEncoder[F[A, B, C, D, E]],
    decoder:      JsonDecoder[F[A, B, C, D, E]],
    classTag:     ClassTag[F[A, B, C, D, E]]
  ): ZTemporalCodec[F[A, B, C, D, E]] =
    new KindN[F[A, B, C, D, E]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E]]],
      new ZParameterizedType(
        classTag.runtimeClass,
        Array(innerA.genericType, innerB.genericType, innerC.genericType, innerD.genericType, innerE.genericType)
      )
    )

  given kind6[F[_, _, _, _, _, _], A, B, C, D, E, G](
    using innerA: ZTemporalCodec[A],
    innerB:       ZTemporalCodec[B],
    innerC:       ZTemporalCodec[C],
    innerD:       ZTemporalCodec[D],
    innerE:       ZTemporalCodec[E],
    innerG:       ZTemporalCodec[G],
    encoder:      JsonEncoder[F[A, B, C, D, E, G]],
    decoder:      JsonDecoder[F[A, B, C, D, E, G]],
    classTag:     ClassTag[F[A, B, C, D, E, G]]
  ): ZTemporalCodec[F[A, B, C, D, E, G]] =
    new KindN[F[A, B, C, D, E, G]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E, G]]],
      new ZParameterizedType(
        classTag.runtimeClass,
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

  given kind7[F[_, _, _, _, _, _, _], A, B, C, D, E, G, H](
    using innerA: ZTemporalCodec[A],
    innerB:       ZTemporalCodec[B],
    innerC:       ZTemporalCodec[C],
    innerD:       ZTemporalCodec[D],
    innerE:       ZTemporalCodec[E],
    innerG:       ZTemporalCodec[G],
    innerH:       ZTemporalCodec[H],
    encoder:      JsonEncoder[F[A, B, C, D, E, G, H]],
    decoder:      JsonDecoder[F[A, B, C, D, E, G, H]],
    classTag:     ClassTag[F[A, B, C, D, E, G, H]]
  ): ZTemporalCodec[F[A, B, C, D, E, G, H]] =
    new KindN[F[A, B, C, D, E, G, H]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E, G, H]]],
      new ZParameterizedType(
        classTag.runtimeClass,
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

  given kind8[F[_, _, _, _, _, _, _, _], A, B, C, D, E, G, H, I](
    using
    innerA:   ZTemporalCodec[A],
    innerB:   ZTemporalCodec[B],
    innerC:   ZTemporalCodec[C],
    innerD:   ZTemporalCodec[D],
    innerE:   ZTemporalCodec[E],
    innerG:   ZTemporalCodec[G],
    innerH:   ZTemporalCodec[H],
    innerI:   ZTemporalCodec[I],
    encoder:  JsonEncoder[F[A, B, C, D, E, G, H, I]],
    decoder:  JsonDecoder[F[A, B, C, D, E, G, H, I]],
    classTag: ClassTag[F[A, B, C, D, E, G, H, I]]
  ): ZTemporalCodec[F[A, B, C, D, E, G, H, I]] =
    new KindN[F[A, B, C, D, E, G, H, I]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E, G, H, I]]],
      new ZParameterizedType(
        classTag.runtimeClass,
        Array(
          innerA.genericType,
          innerB.genericType,
          innerC.genericType,
          innerD.genericType,
          innerE.genericType,
          innerG.genericType,
          innerH.genericType,
          innerI.genericType
        )
      )
    )

  given kind9[F[_, _, _, _, _, _, _, _, _], A, B, C, D, E, G, H, I, J](
    using
    innerA:   ZTemporalCodec[A],
    innerB:   ZTemporalCodec[B],
    innerC:   ZTemporalCodec[C],
    innerD:   ZTemporalCodec[D],
    innerE:   ZTemporalCodec[E],
    innerG:   ZTemporalCodec[G],
    innerH:   ZTemporalCodec[H],
    innerI:   ZTemporalCodec[I],
    innerJ:   ZTemporalCodec[J],
    encoder:  JsonEncoder[F[A, B, C, D, E, G, H, I, J]],
    decoder:  JsonDecoder[F[A, B, C, D, E, G, H, I, J]],
    classTag: ClassTag[F[A, B, C, D, E, G, H, I, J]]
  ): ZTemporalCodec[F[A, B, C, D, E, G, H, I, J]] =
    new KindN[F[A, B, C, D, E, G, H, I, J]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E, G, H, I, J]]],
      new ZParameterizedType(
        classTag.runtimeClass,
        Array(
          innerA.genericType,
          innerB.genericType,
          innerC.genericType,
          innerD.genericType,
          innerE.genericType,
          innerG.genericType,
          innerH.genericType,
          innerI.genericType,
          innerJ.genericType
        )
      )
    )

  given kind10[F[_, _, _, _, _, _, _, _, _, _], A, B, C, D, E, G, H, I, J, K](
    using
    innerA:   ZTemporalCodec[A],
    innerB:   ZTemporalCodec[B],
    innerC:   ZTemporalCodec[C],
    innerD:   ZTemporalCodec[D],
    innerE:   ZTemporalCodec[E],
    innerG:   ZTemporalCodec[G],
    innerH:   ZTemporalCodec[H],
    innerI:   ZTemporalCodec[I],
    innerJ:   ZTemporalCodec[J],
    innerK:   ZTemporalCodec[K],
    encoder:  JsonEncoder[F[A, B, C, D, E, G, H, I, J, K]],
    decoder:  JsonDecoder[F[A, B, C, D, E, G, H, I, J, K]],
    classTag: ClassTag[F[A, B, C, D, E, G, H, I, J, K]]
  ): ZTemporalCodec[F[A, B, C, D, E, G, H, I, J, K]] =
    new KindN[F[A, B, C, D, E, G, H, I, J, K]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E, G, H, I, J, K]]],
      new ZParameterizedType(
        classTag.runtimeClass,
        Array(
          innerA.genericType,
          innerB.genericType,
          innerC.genericType,
          innerD.genericType,
          innerE.genericType,
          innerG.genericType,
          innerH.genericType,
          innerI.genericType,
          innerJ.genericType,
          innerK.genericType
        )
      )
    )

  given kind11[F[_, _, _, _, _, _, _, _, _, _, _], A, B, C, D, E, G, H, I, J, K, L](
    using
    innerA:   ZTemporalCodec[A],
    innerB:   ZTemporalCodec[B],
    innerC:   ZTemporalCodec[C],
    innerD:   ZTemporalCodec[D],
    innerE:   ZTemporalCodec[E],
    innerG:   ZTemporalCodec[G],
    innerH:   ZTemporalCodec[H],
    innerI:   ZTemporalCodec[I],
    innerJ:   ZTemporalCodec[J],
    innerK:   ZTemporalCodec[K],
    innerL:   ZTemporalCodec[L],
    encoder:  JsonEncoder[F[A, B, C, D, E, G, H, I, J, K, L]],
    decoder:  JsonDecoder[F[A, B, C, D, E, G, H, I, J, K, L]],
    classTag: ClassTag[F[A, B, C, D, E, G, H, I, J, K, L]]
  ): ZTemporalCodec[F[A, B, C, D, E, G, H, I, J, K, L]] =
    new KindN[F[A, B, C, D, E, G, H, I, J, K, L]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E, G, H, I, J, K, L]]],
      new ZParameterizedType(
        classTag.runtimeClass,
        Array(
          innerA.genericType,
          innerB.genericType,
          innerC.genericType,
          innerD.genericType,
          innerE.genericType,
          innerG.genericType,
          innerH.genericType,
          innerI.genericType,
          innerJ.genericType,
          innerK.genericType,
          innerL.genericType
        )
      )
    )

  given kind12[F[_, _, _, _, _, _, _, _, _, _, _, _], A, B, C, D, E, G, H, I, J, K, L, M](
    using
    innerA:   ZTemporalCodec[A],
    innerB:   ZTemporalCodec[B],
    innerC:   ZTemporalCodec[C],
    innerD:   ZTemporalCodec[D],
    innerE:   ZTemporalCodec[E],
    innerG:   ZTemporalCodec[G],
    innerH:   ZTemporalCodec[H],
    innerI:   ZTemporalCodec[I],
    innerJ:   ZTemporalCodec[J],
    innerK:   ZTemporalCodec[K],
    innerL:   ZTemporalCodec[L],
    innerM:   ZTemporalCodec[M],
    encoder:  JsonEncoder[F[A, B, C, D, E, G, H, I, J, K, L, M]],
    decoder:  JsonDecoder[F[A, B, C, D, E, G, H, I, J, K, L, M]],
    classTag: ClassTag[F[A, B, C, D, E, G, H, I, J, K, L, M]]
  ): ZTemporalCodec[F[A, B, C, D, E, G, H, I, J, K, L, M]] =
    new KindN[F[A, B, C, D, E, G, H, I, J, K, L, M]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E, G, H, I, J, K, L, M]]],
      new ZParameterizedType(
        classTag.runtimeClass,
        Array(
          innerA.genericType,
          innerB.genericType,
          innerC.genericType,
          innerD.genericType,
          innerE.genericType,
          innerG.genericType,
          innerH.genericType,
          innerI.genericType,
          innerJ.genericType,
          innerK.genericType,
          innerL.genericType,
          innerM.genericType
        )
      )
    )

  given kind13[F[_, _, _, _, _, _, _, _, _, _, _, _, _], A, B, C, D, E, G, H, I, J, K, L, M, N](
    using
    innerA:   ZTemporalCodec[A],
    innerB:   ZTemporalCodec[B],
    innerC:   ZTemporalCodec[C],
    innerD:   ZTemporalCodec[D],
    innerE:   ZTemporalCodec[E],
    innerG:   ZTemporalCodec[G],
    innerH:   ZTemporalCodec[H],
    innerI:   ZTemporalCodec[I],
    innerJ:   ZTemporalCodec[J],
    innerK:   ZTemporalCodec[K],
    innerL:   ZTemporalCodec[L],
    innerM:   ZTemporalCodec[M],
    innerN:   ZTemporalCodec[N],
    encoder:  JsonEncoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N]],
    decoder:  JsonDecoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N]],
    classTag: ClassTag[F[A, B, C, D, E, G, H, I, J, K, L, M, N]]
  ): ZTemporalCodec[F[A, B, C, D, E, G, H, I, J, K, L, M, N]] =
    new KindN[F[A, B, C, D, E, G, H, I, J, K, L, M, N]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E, G, H, I, J, K, L, M, N]]],
      new ZParameterizedType(
        classTag.runtimeClass,
        Array(
          innerA.genericType,
          innerB.genericType,
          innerC.genericType,
          innerD.genericType,
          innerE.genericType,
          innerG.genericType,
          innerH.genericType,
          innerI.genericType,
          innerJ.genericType,
          innerK.genericType,
          innerL.genericType,
          innerM.genericType,
          innerN.genericType
        )
      )
    )

  given kind14[F[_, _, _, _, _, _, _, _, _, _, _, _, _, _], A, B, C, D, E, G, H, I, J, K, L, M, N, O](
    using
    innerA:   ZTemporalCodec[A],
    innerB:   ZTemporalCodec[B],
    innerC:   ZTemporalCodec[C],
    innerD:   ZTemporalCodec[D],
    innerE:   ZTemporalCodec[E],
    innerG:   ZTemporalCodec[G],
    innerH:   ZTemporalCodec[H],
    innerI:   ZTemporalCodec[I],
    innerJ:   ZTemporalCodec[J],
    innerK:   ZTemporalCodec[K],
    innerL:   ZTemporalCodec[L],
    innerM:   ZTemporalCodec[M],
    innerN:   ZTemporalCodec[N],
    innerO:   ZTemporalCodec[O],
    encoder:  JsonEncoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O]],
    decoder:  JsonDecoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O]],
    classTag: ClassTag[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O]]
  ): ZTemporalCodec[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O]] =
    new KindN[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O]]],
      new ZParameterizedType(
        classTag.runtimeClass,
        Array(
          innerA.genericType,
          innerB.genericType,
          innerC.genericType,
          innerD.genericType,
          innerE.genericType,
          innerG.genericType,
          innerH.genericType,
          innerI.genericType,
          innerJ.genericType,
          innerK.genericType,
          innerL.genericType,
          innerM.genericType,
          innerN.genericType,
          innerO.genericType
        )
      )
    )

  given kind15[F[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _], A, B, C, D, E, G, H, I, J, K, L, M, N, O, P](
    using
    innerA:   ZTemporalCodec[A],
    innerB:   ZTemporalCodec[B],
    innerC:   ZTemporalCodec[C],
    innerD:   ZTemporalCodec[D],
    innerE:   ZTemporalCodec[E],
    innerG:   ZTemporalCodec[G],
    innerH:   ZTemporalCodec[H],
    innerI:   ZTemporalCodec[I],
    innerJ:   ZTemporalCodec[J],
    innerK:   ZTemporalCodec[K],
    innerL:   ZTemporalCodec[L],
    innerM:   ZTemporalCodec[M],
    innerN:   ZTemporalCodec[N],
    innerO:   ZTemporalCodec[O],
    innerP:   ZTemporalCodec[P],
    encoder:  JsonEncoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P]],
    decoder:  JsonDecoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P]],
    classTag: ClassTag[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P]]
  ): ZTemporalCodec[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P]] =
    new KindN[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P]]],
      new ZParameterizedType(
        classTag.runtimeClass,
        Array(
          innerA.genericType,
          innerB.genericType,
          innerC.genericType,
          innerD.genericType,
          innerE.genericType,
          innerG.genericType,
          innerH.genericType,
          innerI.genericType,
          innerJ.genericType,
          innerK.genericType,
          innerL.genericType,
          innerM.genericType,
          innerN.genericType,
          innerO.genericType,
          innerP.genericType
        )
      )
    )

  given kind16[F[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _], A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q](
    using
    innerA:   ZTemporalCodec[A],
    innerB:   ZTemporalCodec[B],
    innerC:   ZTemporalCodec[C],
    innerD:   ZTemporalCodec[D],
    innerE:   ZTemporalCodec[E],
    innerG:   ZTemporalCodec[G],
    innerH:   ZTemporalCodec[H],
    innerI:   ZTemporalCodec[I],
    innerJ:   ZTemporalCodec[J],
    innerK:   ZTemporalCodec[K],
    innerL:   ZTemporalCodec[L],
    innerM:   ZTemporalCodec[M],
    innerN:   ZTemporalCodec[N],
    innerO:   ZTemporalCodec[O],
    innerP:   ZTemporalCodec[P],
    innerQ:   ZTemporalCodec[Q],
    encoder:  JsonEncoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q]],
    decoder:  JsonDecoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q]],
    classTag: ClassTag[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q]]
  ): ZTemporalCodec[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q]] =
    new KindN[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q]]],
      new ZParameterizedType(
        classTag.runtimeClass,
        Array(
          innerA.genericType,
          innerB.genericType,
          innerC.genericType,
          innerD.genericType,
          innerE.genericType,
          innerG.genericType,
          innerH.genericType,
          innerI.genericType,
          innerJ.genericType,
          innerK.genericType,
          innerL.genericType,
          innerM.genericType,
          innerN.genericType,
          innerO.genericType,
          innerP.genericType,
          innerQ.genericType
        )
      )
    )

  given kind17[F[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _], A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R](
    using
    innerA:   ZTemporalCodec[A],
    innerB:   ZTemporalCodec[B],
    innerC:   ZTemporalCodec[C],
    innerD:   ZTemporalCodec[D],
    innerE:   ZTemporalCodec[E],
    innerG:   ZTemporalCodec[G],
    innerH:   ZTemporalCodec[H],
    innerI:   ZTemporalCodec[I],
    innerJ:   ZTemporalCodec[J],
    innerK:   ZTemporalCodec[K],
    innerL:   ZTemporalCodec[L],
    innerM:   ZTemporalCodec[M],
    innerN:   ZTemporalCodec[N],
    innerO:   ZTemporalCodec[O],
    innerP:   ZTemporalCodec[P],
    innerQ:   ZTemporalCodec[Q],
    innerR:   ZTemporalCodec[R],
    encoder:  JsonEncoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R]],
    decoder:  JsonDecoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R]],
    classTag: ClassTag[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R]]
  ): ZTemporalCodec[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R]] =
    new KindN[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R]]],
      new ZParameterizedType(
        classTag.runtimeClass,
        Array(
          innerA.genericType,
          innerB.genericType,
          innerC.genericType,
          innerD.genericType,
          innerE.genericType,
          innerG.genericType,
          innerH.genericType,
          innerI.genericType,
          innerJ.genericType,
          innerK.genericType,
          innerL.genericType,
          innerM.genericType,
          innerN.genericType,
          innerO.genericType,
          innerP.genericType,
          innerQ.genericType,
          innerR.genericType
        )
      )
    )

  given kind18[
    F[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _],
    A,
    B,
    C,
    D,
    E,
    G,
    H,
    I,
    J,
    K,
    L,
    M,
    N,
    O,
    P,
    Q,
    R,
    S
  ](using
    innerA:   ZTemporalCodec[A],
    innerB:   ZTemporalCodec[B],
    innerC:   ZTemporalCodec[C],
    innerD:   ZTemporalCodec[D],
    innerE:   ZTemporalCodec[E],
    innerG:   ZTemporalCodec[G],
    innerH:   ZTemporalCodec[H],
    innerI:   ZTemporalCodec[I],
    innerJ:   ZTemporalCodec[J],
    innerK:   ZTemporalCodec[K],
    innerL:   ZTemporalCodec[L],
    innerM:   ZTemporalCodec[M],
    innerN:   ZTemporalCodec[N],
    innerO:   ZTemporalCodec[O],
    innerP:   ZTemporalCodec[P],
    innerQ:   ZTemporalCodec[Q],
    innerR:   ZTemporalCodec[R],
    innerS:   ZTemporalCodec[S],
    encoder:  JsonEncoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S]],
    decoder:  JsonDecoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S]],
    classTag: ClassTag[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S]]
  ): ZTemporalCodec[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S]] =
    new KindN[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S]]],
      new ZParameterizedType(
        classTag.runtimeClass,
        Array(
          innerA.genericType,
          innerB.genericType,
          innerC.genericType,
          innerD.genericType,
          innerE.genericType,
          innerG.genericType,
          innerH.genericType,
          innerI.genericType,
          innerJ.genericType,
          innerK.genericType,
          innerL.genericType,
          innerM.genericType,
          innerN.genericType,
          innerO.genericType,
          innerP.genericType,
          innerQ.genericType,
          innerR.genericType,
          innerS.genericType
        )
      )
    )

  given kind19[
    F[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _],
    A,
    B,
    C,
    D,
    E,
    G,
    H,
    I,
    J,
    K,
    L,
    M,
    N,
    O,
    P,
    Q,
    R,
    S,
    T
  ](using
    innerA:   ZTemporalCodec[A],
    innerB:   ZTemporalCodec[B],
    innerC:   ZTemporalCodec[C],
    innerD:   ZTemporalCodec[D],
    innerE:   ZTemporalCodec[E],
    innerG:   ZTemporalCodec[G],
    innerH:   ZTemporalCodec[H],
    innerI:   ZTemporalCodec[I],
    innerJ:   ZTemporalCodec[J],
    innerK:   ZTemporalCodec[K],
    innerL:   ZTemporalCodec[L],
    innerM:   ZTemporalCodec[M],
    innerN:   ZTemporalCodec[N],
    innerO:   ZTemporalCodec[O],
    innerP:   ZTemporalCodec[P],
    innerQ:   ZTemporalCodec[Q],
    innerR:   ZTemporalCodec[R],
    innerS:   ZTemporalCodec[S],
    innerT:   ZTemporalCodec[T],
    encoder:  JsonEncoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T]],
    decoder:  JsonDecoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T]],
    classTag: ClassTag[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T]]
  ): ZTemporalCodec[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T]] =
    new KindN[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T]]],
      new ZParameterizedType(
        classTag.runtimeClass,
        Array(
          innerA.genericType,
          innerB.genericType,
          innerC.genericType,
          innerD.genericType,
          innerE.genericType,
          innerG.genericType,
          innerH.genericType,
          innerI.genericType,
          innerJ.genericType,
          innerK.genericType,
          innerL.genericType,
          innerM.genericType,
          innerN.genericType,
          innerO.genericType,
          innerP.genericType,
          innerQ.genericType,
          innerR.genericType,
          innerS.genericType,
          innerT.genericType
        )
      )
    )

  given kind20[
    F[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _],
    A,
    B,
    C,
    D,
    E,
    G,
    H,
    I,
    J,
    K,
    L,
    M,
    N,
    O,
    P,
    Q,
    R,
    S,
    T,
    U
  ](using
    innerA:   ZTemporalCodec[A],
    innerB:   ZTemporalCodec[B],
    innerC:   ZTemporalCodec[C],
    innerD:   ZTemporalCodec[D],
    innerE:   ZTemporalCodec[E],
    innerG:   ZTemporalCodec[G],
    innerH:   ZTemporalCodec[H],
    innerI:   ZTemporalCodec[I],
    innerJ:   ZTemporalCodec[J],
    innerK:   ZTemporalCodec[K],
    innerL:   ZTemporalCodec[L],
    innerM:   ZTemporalCodec[M],
    innerN:   ZTemporalCodec[N],
    innerO:   ZTemporalCodec[O],
    innerP:   ZTemporalCodec[P],
    innerQ:   ZTemporalCodec[Q],
    innerR:   ZTemporalCodec[R],
    innerS:   ZTemporalCodec[S],
    innerT:   ZTemporalCodec[T],
    innerU:   ZTemporalCodec[U],
    encoder:  JsonEncoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U]],
    decoder:  JsonDecoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U]],
    classTag: ClassTag[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U]]
  ): ZTemporalCodec[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U]] =
    new KindN[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U]]],
      new ZParameterizedType(
        classTag.runtimeClass,
        Array(
          innerA.genericType,
          innerB.genericType,
          innerC.genericType,
          innerD.genericType,
          innerE.genericType,
          innerG.genericType,
          innerH.genericType,
          innerI.genericType,
          innerJ.genericType,
          innerK.genericType,
          innerL.genericType,
          innerM.genericType,
          innerN.genericType,
          innerO.genericType,
          innerP.genericType,
          innerQ.genericType,
          innerR.genericType,
          innerS.genericType,
          innerT.genericType,
          innerU.genericType
        )
      )
    )

  given kind21[
    F[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _],
    A,
    B,
    C,
    D,
    E,
    G,
    H,
    I,
    J,
    K,
    L,
    M,
    N,
    O,
    P,
    Q,
    R,
    S,
    T,
    U,
    V
  ](using
    innerA:   ZTemporalCodec[A],
    innerB:   ZTemporalCodec[B],
    innerC:   ZTemporalCodec[C],
    innerD:   ZTemporalCodec[D],
    innerE:   ZTemporalCodec[E],
    innerG:   ZTemporalCodec[G],
    innerH:   ZTemporalCodec[H],
    innerI:   ZTemporalCodec[I],
    innerJ:   ZTemporalCodec[J],
    innerK:   ZTemporalCodec[K],
    innerL:   ZTemporalCodec[L],
    innerM:   ZTemporalCodec[M],
    innerN:   ZTemporalCodec[N],
    innerO:   ZTemporalCodec[O],
    innerP:   ZTemporalCodec[P],
    innerQ:   ZTemporalCodec[Q],
    innerR:   ZTemporalCodec[R],
    innerS:   ZTemporalCodec[S],
    innerT:   ZTemporalCodec[T],
    innerU:   ZTemporalCodec[U],
    innerV:   ZTemporalCodec[V],
    encoder:  JsonEncoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V]],
    decoder:  JsonDecoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V]],
    classTag: ClassTag[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V]]
  ): ZTemporalCodec[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V]] =
    new KindN[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V]]],
      new ZParameterizedType(
        classTag.runtimeClass,
        Array(
          innerA.genericType,
          innerB.genericType,
          innerC.genericType,
          innerD.genericType,
          innerE.genericType,
          innerG.genericType,
          innerH.genericType,
          innerI.genericType,
          innerJ.genericType,
          innerK.genericType,
          innerL.genericType,
          innerM.genericType,
          innerN.genericType,
          innerO.genericType,
          innerP.genericType,
          innerQ.genericType,
          innerR.genericType,
          innerS.genericType,
          innerT.genericType,
          innerU.genericType,
          innerV.genericType
        )
      )
    )

  given kind22[
    F[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _],
    A,
    B,
    C,
    D,
    E,
    G,
    H,
    I,
    J,
    K,
    L,
    M,
    N,
    O,
    P,
    Q,
    R,
    S,
    T,
    U,
    V,
    W
  ](using
    innerA:   ZTemporalCodec[A],
    innerB:   ZTemporalCodec[B],
    innerC:   ZTemporalCodec[C],
    innerD:   ZTemporalCodec[D],
    innerE:   ZTemporalCodec[E],
    innerG:   ZTemporalCodec[G],
    innerH:   ZTemporalCodec[H],
    innerI:   ZTemporalCodec[I],
    innerJ:   ZTemporalCodec[J],
    innerK:   ZTemporalCodec[K],
    innerL:   ZTemporalCodec[L],
    innerM:   ZTemporalCodec[M],
    innerN:   ZTemporalCodec[N],
    innerO:   ZTemporalCodec[O],
    innerP:   ZTemporalCodec[P],
    innerQ:   ZTemporalCodec[Q],
    innerR:   ZTemporalCodec[R],
    innerS:   ZTemporalCodec[S],
    innerT:   ZTemporalCodec[T],
    innerU:   ZTemporalCodec[U],
    innerV:   ZTemporalCodec[V],
    innerW:   ZTemporalCodec[W],
    encoder:  JsonEncoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W]],
    decoder:  JsonDecoder[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W]],
    classTag: ClassTag[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W]]
  ): ZTemporalCodec[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W]] =
    new KindN[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W]](
      encoder,
      decoder,
      classTag.runtimeClass.asInstanceOf[Class[F[A, B, C, D, E, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W]]],
      new ZParameterizedType(
        classTag.runtimeClass,
        Array(
          innerA.genericType,
          innerB.genericType,
          innerC.genericType,
          innerD.genericType,
          innerE.genericType,
          innerG.genericType,
          innerH.genericType,
          innerI.genericType,
          innerJ.genericType,
          innerK.genericType,
          innerL.genericType,
          innerM.genericType,
          innerN.genericType,
          innerO.genericType,
          innerP.genericType,
          innerQ.genericType,
          innerR.genericType,
          innerS.genericType,
          innerT.genericType,
          innerU.genericType,
          innerV.genericType,
          innerW.genericType
        )
      )
    )

}

/** Ground-kind fallback. Lower priority than the generic derivations so that parameterized types get precise
  * `ParameterizedType` keys instead of the raw class.
  */
private[json] trait LowPriorityZTemporalCodecInstances1 {
  given kind0[A](using encoder: JsonEncoder[A], decoder: JsonDecoder[A], classTag: ClassTag[A]): ZTemporalCodec[A] =
    new ZTemporalCodec.Kind0[A](encoder, decoder)
}
