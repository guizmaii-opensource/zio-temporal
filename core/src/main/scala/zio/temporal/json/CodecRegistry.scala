package zio.temporal.json

import zio.json.{JsonDecoder, JsonEncoder}

import java.lang.reflect.{ParameterizedType, Type}
import java.util.concurrent.ConcurrentHashMap

/** Thread-safe (per-index under append-only use) registry that maps runtime types to the zio-json encoders/decoders
  * that [[ZioJsonPayloadConverter]] uses to cross a Temporal boundary.
  *
  * '''Concurrency contract.''' Each of the three indexes (`byClass`, `byType`, `byRawClass`) is individually safe for
  * concurrent access — a hit after a completed `register` is always seen by every reader. Cross-index visibility
  * ''during'' a single `register` call is '''not''' atomic: a concurrent lookup may transiently see one index updated
  * while another hasn't yet observed the same codec. Under the stated append-only usage the only observable effect is a
  * transient miss (retriable by the caller); no partial overwrite is possible.
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
    // Consistency gate: whether the codec is ground or parameterized, `klass` must be the raw runtime class of
    // `genericType`. A mismatch would let the registry index the codec under a key that `encoderForClass` or
    // `decoderForType` can never produce, making the codec silently unreachable, OR under a key shared with an
    // unrelated type (polluting dispatch). Both hazards can be introduced by a hand-built `extends ZTemporalCodec`.
    require(
      codec.genericType match {
        case pt: ParameterizedType => pt.getRawType == codec.klass
        case t                     => t == codec.klass
      },
      s"ZTemporalCodec.klass (${codec.klass.getName}) does not match the raw type of " +
        s"ZTemporalCodec.genericType (${codec.genericType.getTypeName}). Build parameterized codecs via the " +
        "kindN givens and ground codecs via `ZTemporalCodec.make[A]` so these stay in sync by construction."
    )
    if (codec.genericType == codec.klass) {
      byClass.put(codec.klass, (codec.encoder, codec.decoder))
      // Also index every boxed counterpart. Java erases Scala primitives (`Int`/`Long`/...) to
      // `int.class`/`long.class`/... at the `ClassTag` level, but any value that actually reaches the
      // `DataConverter.toPayload(Object)` call site is boxed — and the Scala boxing differs from the
      // Java one in two places worth knowing:
      //   - `Unit` erases to `void.class`. Its Java counterpart is `java.lang.Void`, but no Scala `()`
      //     value is ever a `java.lang.Void`; `()` is always `scala.runtime.BoxedUnit.UNIT`.
      //   - all other primitives map to their `java.lang.*` boxed class as usual.
      // Without these extra index entries, encoding a `()` or an `Int` / `Long` / ... value misses the
      // codec entirely.
      val iterator = boxedOf(codec.klass).iterator
      while (iterator.hasNext) {
        byClass.put(iterator.next(), (codec.encoder, codec.decoder))
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

  /** Returns every boxed runtime class that can stand in for a Scala/Java primitive class at `DataConverter.toPayload`
    * time. For most primitives this is just the `java.lang.*` wrapper; for `Unit` it's `scala.runtime.BoxedUnit`
    * (Scala's `()` boxes to `BoxedUnit.UNIT`, never `java.lang.Void`). Returns `Nil` for non-primitive classes.
    */
  private def boxedOf(cls: Class[_]): List[Class[_]] = {
    if (cls eq java.lang.Integer.TYPE) List(classOf[java.lang.Integer])
    else if (cls eq java.lang.Long.TYPE) List(classOf[java.lang.Long])
    else if (cls eq java.lang.Boolean.TYPE) List(classOf[java.lang.Boolean])
    else if (cls eq java.lang.Double.TYPE) List(classOf[java.lang.Double])
    else if (cls eq java.lang.Float.TYPE) List(classOf[java.lang.Float])
    else if (cls eq java.lang.Short.TYPE) List(classOf[java.lang.Short])
    else if (cls eq java.lang.Byte.TYPE) List(classOf[java.lang.Byte])
    else if (cls eq java.lang.Character.TYPE) List(classOf[java.lang.Character])
    else if (cls eq java.lang.Void.TYPE) List(classOf[scala.runtime.BoxedUnit], classOf[java.lang.Void])
    else Nil
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
