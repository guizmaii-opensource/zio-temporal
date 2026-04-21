package zio.temporal.json

import zio.json.{JsonDecoder, JsonEncoder}

import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

/** Thread-safe registry that maps runtime types to the zio-json encoders/decoders that [[ZioJsonPayloadConverter]] uses
  * to cross a Temporal boundary.
  *
  * The registry is indexed three ways:
  *
  *   - By [[java.lang.Class]] on the '''encode''' path (`byClass`). Temporal hands the `DataConverter` only
  *     `v.getClass`, so we must look up the encoder from that. Only ground-type codecs go here; see [[register]] for
  *     the rationale on keeping parameterized types out.
  *   - By [[java.lang.reflect.Type]] on the '''decode''' path (`byType`). Temporal hands the `DataConverter` a fully
  *     parameterized type (from the stub's method signature) that carries generic arguments — so `List[Foo]` and
  *     `List[Bar]` are distinguishable here.
  *   - By raw [[java.lang.Class]] with all parameterized-type candidates collected in a list (`byRawClass`). This is
  *     the fallback the encode path uses for user-defined generic case classes like `Triple[A, B, C]` where neither a
  *     direct byClass hit nor the container-iteration escape hatch applies. When a single parameterized candidate
  *     exists for a given raw class, its encoder is used; when multiple candidates exist, encode fails with a clear
  *     error listing both registered parameterized types.
  *
  * All three views are populated in a single call to `register` so they stay in sync. Registrations are append-only:
  * once a `(class, type, codec)` triple is in, it does not change.
  *
  * In normal use the registry is populated at client/worker-construction time by zio-temporal's macros walking each
  * workflow and activity interface. Manual registration via `register` is available for edge cases (e.g. an ad-hoc
  * untyped stub).
  */
final class CodecRegistry {

  private val byClass    = new ConcurrentHashMap[Class[_], (JsonEncoder[_], JsonDecoder[_])]()
  private val byType     = new ConcurrentHashMap[Type, (JsonEncoder[_], JsonDecoder[_])]()
  private val byRawClass = new ConcurrentHashMap[Class[_], java.util.List[CodecRegistry.ParamEntry]]()

  /** Register a codec.
    *
    * Ground types (where `codec.genericType == codec.klass`) are indexed in `byClass` keyed on the runtime class for
    * encode-side lookup and in `byType` keyed on the same class for decode-side symmetry.
    *
    * Parameterized types (e.g. `ZTemporalCodec[List[Foo]]`) are indexed in `byType` keyed on the full
    * `ParameterizedType` and in `byRawClass` keyed on the raw runtime class. They are ''not'' added to `byClass`
    * because every `List[X]` erases to the same raw `classOf[List]` at runtime — indexing them there would let the
    * last-registered `List[X]` silently overwrite every other, and encoding any `List[_]` value would pick up the wrong
    * element encoder, producing corrupted JSON.
    *
    * The `byRawClass` side-index keeps the full set of parameterized candidates per raw class so the encode path can
    * choose deterministically. For well-known containers (`List`, `Vector`, `Set`, `Map`, `Option`) the converter
    * recurses into elements and never consults `byRawClass`. For user-defined generics (`Triple[A, B, C]`) the fallback
    * is used only when exactly one parameterized instantiation is registered for the raw class; multiple candidates
    * produce a clear encode-time error. See [[ZioJsonPayloadConverter.encodeValue]].
    */
  def register[A](codec: ZTemporalCodec[A]): this.type = {
    if (codec.genericType == codec.klass) {
      byClass.put(codec.klass, (codec.encoder, codec.decoder))
      // Also index the boxed counterpart for primitive classes — Java erases Scala `Int`/`Long`/... to
      // `int.class`/`long.class`/... at the `ClassTag` level, but any value that actually reaches the
      // `DataConverter.toPayload(Object)` call site is boxed (`classOf[Integer]`, `classOf[Long]`, ...).
      // Without this, `encoderForClass(classOf[Integer])` misses the `Int` codec entirely.
      boxedOf(codec.klass) match {
        case null  => ()
        case boxed => byClass.put(boxed, (codec.encoder, codec.decoder))
      }
    } else {
      val entry = CodecRegistry.ParamEntry(codec.genericType, codec.encoder, codec.decoder)
      byRawClass.compute(
        codec.klass,
        (_, existing) => {
          val list =
            if (existing eq null) new java.util.concurrent.CopyOnWriteArrayList[CodecRegistry.ParamEntry]()
            else existing
          // Dedupe on the generic type so repeat registrations don't multiply candidates.
          val genericType = codec.genericType
          val iterator    = list.iterator()
          var found       = false
          while (!found && iterator.hasNext) {
            if (iterator.next().genericType == genericType) found = true
          }
          if (!found) list.add(entry)
          list
        }
      )
    }
    byType.put(codec.genericType, (codec.encoder, codec.decoder))
    this
  }

  /** Returns the boxed wrapper class for a Scala/Java primitive class, or `null` if `cls` isn't primitive. */
  private def boxedOf(cls: Class[_]): Class[_] | Null = {
    if (cls eq java.lang.Integer.TYPE) classOf[java.lang.Integer]
    else if (cls eq java.lang.Long.TYPE) classOf[java.lang.Long]
    else if (cls eq java.lang.Boolean.TYPE) classOf[java.lang.Boolean]
    else if (cls eq java.lang.Double.TYPE) classOf[java.lang.Double]
    else if (cls eq java.lang.Float.TYPE) classOf[java.lang.Float]
    else if (cls eq java.lang.Short.TYPE) classOf[java.lang.Short]
    else if (cls eq java.lang.Byte.TYPE) classOf[java.lang.Byte]
    else if (cls eq java.lang.Character.TYPE) classOf[java.lang.Character]
    else if (cls eq java.lang.Void.TYPE) classOf[java.lang.Void]
    else null
  }

  /** Look up an encoder by the value's runtime class. Returns `null` for "not found" to avoid allocating an `Option` on
    * the hot serialization path.
    *
    * If there is no exact match for `cls`, walks the class's supertype chain — first the superclass chain, then the
    * implemented interfaces — looking for a registered codec on any ancestor. This covers two common patterns:
    *
    *   1. Scala collections expose concrete subclasses that differ from the registered static type. A non-empty
    *      `List[A]` has runtime class `scala.collection.immutable.$colon$colon`, while the caller registered
    *      `scala.collection.immutable.List`. The superclass walk resolves the registered base codec.
    *   2. A user may register a codec on an interface (`sealed trait Shape`) and serialize concrete implementations
    *      (`Rectangle(...)`). The interface walk finds the registered `Shape` codec even when the concrete class itself
    *      was never explicitly registered.
    *
    * This matches the Jackson-era behaviour most users relied on.
    */
  def encoderForClass(cls: Class[_]): JsonEncoder[_] | Null = {
    var c: Class[_] = cls
    while (c ne null) {
      val hit = byClass.get(c)
      if (hit ne null) return hit._1
      c = c.getSuperclass
    }
    // Superclass chain didn't match — try implemented interfaces. Java's `Class#getInterfaces` only returns
    // the directly-declared interfaces, so we walk transitively ourselves.
    val visited = new java.util.HashSet[Class[_]](8)
    val stack   = new java.util.ArrayDeque[Class[_]](8)
    var next    = cls
    while (next ne null) {
      val directs = next.getInterfaces
      var index   = 0
      while (index < directs.length) {
        stack.push(directs(index))
        index += 1
      }
      next = next.getSuperclass
    }
    while (!stack.isEmpty) {
      val iface = stack.pop()
      if (visited.add(iface)) {
        val hit = byClass.get(iface)
        if (hit ne null) return hit._1
        val supers = iface.getInterfaces
        var index  = 0
        while (index < supers.length) {
          stack.push(supers(index))
          index += 1
        }
      }
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

  /** Look up a decoder by the value's runtime class, walking the superclass chain and implemented interfaces to find a
    * registered ancestor. Mirrors [[encoderForClass]] for the decode path: when a concrete subtype of a sealed
    * hierarchy (e.g. `ParameterizedWorkflowInput.Soda`) reaches the converter but only the sealed parent was
    * registered, the walk finds the parent codec. Queries [[byType]] since `java.lang.Class[_]` is a
    * [[java.lang.reflect.Type]] and ground codecs are indexed there under their class key.
    */
  def decoderForClassHierarchy(cls: Class[_]): JsonDecoder[_] | Null = {
    var c: Class[_] = cls
    while (c ne null) {
      val hit = byType.get(c)
      if (hit ne null) return hit._2
      c = c.getSuperclass
    }
    val visited = new java.util.HashSet[Class[_]](8)
    val stack   = new java.util.ArrayDeque[Class[_]](8)
    var next    = cls
    while (next ne null) {
      val directs = next.getInterfaces
      var index   = 0
      while (index < directs.length) {
        stack.push(directs(index))
        index += 1
      }
      next = next.getSuperclass
    }
    while (!stack.isEmpty) {
      val iface = stack.pop()
      if (visited.add(iface)) {
        val hit = byType.get(iface)
        if (hit ne null) return hit._2
        val supers = iface.getInterfaces
        var index  = 0
        while (index < supers.length) {
          stack.push(supers(index))
          index += 1
        }
      }
    }
    null
  }

  /** Returns the list of parameterized-type candidates registered for the given raw runtime class, or `null` if no
    * parameterized codec was registered for it. The caller decides what to do with multiple candidates (the
    * `ZioJsonPayloadConverter` encode path fails loudly when more than one exists, to avoid silent wrong-codec encoding
    * for user-defined generics like `Triple[A, B, C]`).
    */
  private[json] def parameterizedCandidatesForClass(cls: Class[_]): java.util.List[CodecRegistry.ParamEntry] | Null =
    byRawClass.get(cls)

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

  /** One registered parameterized-type codec candidate. Held per raw class in `byRawClass`. */
  private[json] final case class ParamEntry(genericType: Type, encoder: JsonEncoder[_], decoder: JsonDecoder[_])

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
