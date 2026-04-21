package zio.temporal.json

import zio.json.{JsonDecoder, JsonEncoder}

import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

/** Thread-safe registry that maps runtime types to the zio-json encoders/decoders that [[ZioJsonPayloadConverter]] uses
  * to cross a Temporal boundary.
  *
  * The registry is indexed two ways:
  *
  *   - By [[java.lang.Class]], on the '''encode''' path. Temporal hands the `DataConverter` only `v.getClass`, so we
  *     must look up the encoder from that.
  *   - By [[java.lang.reflect.Type]], on the '''decode''' path. Temporal hands the `DataConverter` a fully
  *     parameterized type (from the stub's method signature) that carries generic arguments — so `List[Foo]` and
  *     `List[Bar]` are distinguishable here.
  *
  * Both views are populated in a single call to `register` so they stay in sync. Registrations are append-only: once a
  * `(class, type, codec)` triple is in, it does not change.
  *
  * In normal use the registry is populated at client/worker-construction time by zio-temporal's macros walking each
  * workflow and activity interface. Manual registration via `register` is available for edge cases (e.g. an ad-hoc
  * untyped stub).
  */
final class CodecRegistry {

  private val byClass = new ConcurrentHashMap[Class[_], (JsonEncoder[_], JsonDecoder[_])]()
  private val byType  = new ConcurrentHashMap[Type, (JsonEncoder[_], JsonDecoder[_])]()

  /** Register a codec. Safe to call repeatedly; a later call with the same key wins.
    *
    * Indexes by both the raw runtime class (for encode) and the generic `Type` (for decode). When `codec.genericType`
    * equals `codec.klass` (a ground type), only the class mapping is effective.
    */
  def register[A](codec: ZTemporalCodec[A]): this.type = {
    byClass.put(codec.klass, (codec.encoder, codec.decoder))
    byType.put(codec.genericType, (codec.encoder, codec.decoder))
    this
  }

  /** Look up an encoder by the value's runtime class, walking up the superclass chain if no exact match is found.
    * Returns `null` for "not found" to avoid allocating an `Option` on the hot serialization path.
    *
    * Many Scala collections expose concrete runtime classes that differ from the static type registered — for example a
    * non-empty `List[A]` has runtime class `scala.collection.immutable.$colon$colon`, while the caller registered
    * `scala.collection.immutable.List`. Walking the superclass chain lets the encoder registered for the base type
    * serve all runtime subclasses, which matches the Jackson-era behaviour most users relied on.
    */
  def encoderForClass(cls: Class[_]): JsonEncoder[_] | Null = {
    var c: Class[_] = cls
    while (c ne null) {
      val hit = byClass.get(c)
      if (hit ne null) return hit._1
      c = c.getSuperclass
    }
    null
  }

  /** Look up a decoder by a parameterized Java type. Returns `null` for "not found" to avoid allocating an `Option` on
    * the hot deserialization path.
    */
  def decoderForType(t: Type): JsonDecoder[_] | Null = {
    val hit = byType.get(t)
    if (hit ne null) hit._2 else null
  }

  /** For diagnostics: human-readable list of registered types. */
  def registeredTypeNames: Iterable[String] = {
    import scala.jdk.CollectionConverters._
    byType.keySet().asScala.map(_.getTypeName)
  }

  /** For diagnostics: runtime classes indexed in the encode path. */
  def registeredClassNames: Iterable[String] = {
    import scala.jdk.CollectionConverters._
    byClass.keySet().asScala.map(_.getName)
  }

  /** Number of registered types. */
  def size: Int = byType.size()

  /** Walk the workflow or activity interface `I` at compile time and register a codec for every parameter and return
    * type of every method annotated with `@workflowMethod` / `@signalMethod` / `@queryMethod` / `@activityMethod`.
    *
    * Compilation fails with a clear message if any of those types lacks a `ZTemporalCodec` in scope, so a worker or
    * client that is missing runtime registrations cannot be assembled.
    *
    * Returns `this` so calls can be chained:
    *
    * {{{
    *   val registry = new CodecRegistry()
    *     .addInterface[PaymentWorkflow]
    *     .addInterface[PaymentActivity]
    * }}}
    *
    * Inherited `@workflowMethod`s are included, so `SodaWorkflow extends ParameterizedWorkflow[Soda]` correctly
    * registers codecs for the parent's abstract method.
    */
  inline def addInterface[I]: CodecRegistry =
    ${ zio.temporal.internal.InterfaceCodecsMacros.addInterfaceImpl[I]('this) }
}

object CodecRegistry {

  /** Build a registry pre-populated with the given codecs. The typical usage from a client or worker setup:
    *
    * {{{
    *   val registry = CodecRegistry.of(
    *     ZTemporalCodec[MyInput],
    *     ZTemporalCodec[MyResult],
    *     ZTemporalCodec[List[MyInput]]
    *   )
    *   ZWorkflowClientOptions.make @@ ZWorkflowClientOptions.withCodecRegistry(registry)
    * }}}
    */
  def of(codecs: ZTemporalCodec[_]*): CodecRegistry = {
    val r = new CodecRegistry()
    codecs.foreach(r.register(_))
    r
  }
}
