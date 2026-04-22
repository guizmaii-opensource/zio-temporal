package zio.temporal.internal

import zio.temporal.json.{CodecRegistry, ZTemporalCodec}
import zio.temporal.{activityInterface, activityMethod, queryMethod, signalMethod, workflowInterface, workflowMethod}

import scala.quoted.*

/** Compile-time helpers for auto-registering zio-json codecs from a workflow or activity interface's method signatures.
  *
  * The macro walks a type `I` — expected to be a `@workflowInterface` or `@activityInterface` — and for every method
  * annotated with `@workflowMethod`, `@signalMethod`, `@queryMethod`, or `@activityMethod`, summons a
  * `ZTemporalCodec[T]` for each parameter type and for the return type. If any required codec cannot be summoned,
  * compilation fails with a message pointing at the type that needs one.
  *
  * The summoned codecs are emitted as runtime registrations into the supplied [[CodecRegistry]]. This is what makes
  * `CodecRegistry#addInterface[I]` work end-to-end: compile-time evidence of codec existence + runtime registration in
  * one call.
  *
  * The same machinery is used by the ''auto-registration'' path (`autoRegisterInterfaceImpl`,
  * `autoRegisterActivityImplImpl`), which is invoked from `ZWorker.addWorkflow[I]`,
  * `ZWorker.addActivityImplementation`, `ZWorkflowClient.newWorkflowStub[A]`, etc. to remove the need for users to call
  * `.addInterface[...]` by hand.
  */
object InterfaceCodecsMacros {

  /** Implementation of `CodecRegistry#addInterface[I]`. Walks `I`, summons codecs, emits a chained
    * `registry.register(...)` for each, and returns the (same) registry expression.
    */
  def addInterfaceImpl[I: Type](registry: Expr[CodecRegistry])(using q: Quotes): Expr[CodecRegistry] = {
    import q.reflect.*
    val helpers = new InterfaceCodecsHelpers[q.type]
    val codecs  = helpers.collectInterfaceCodecs[I](
      interfaceSym = TypeRepr.of[I].typeSymbol,
      context = "auto-register"
    )
    helpers.foldRegistrations(registry, codecs)
  }

  /** Implementation of the auto-registration call site for an interface. Emits
    * `registryOpt.foreach { r => r.register(codec1); r.register(codec2); ... }` at compile time. Opt-out
    * (`codecRegistry = None`) is a silent no-op because the `foreach` body is empty.
    *
    * Accepts either a `@workflowInterface`-annotated type (common case) ''or'' a workflow ''implementation'' class
    * whose supertypes include a `@workflowInterface`. Impl classes show up at `ZWorker.addWorkflow[Impl].fromClass`
    * call sites — the user's `Impl` satisfies `ExtendsWorkflow` via ancestry but doesn't carry `@workflowMethod`
    * annotations directly. We walk its base classes for `@workflowInterface`- or `@activityInterface`-annotated
    * ancestors and register each interface's codecs.
    */
  def autoRegisterInterfaceImpl[I: Type](
    registryOpt: Expr[Option[CodecRegistry]]
  )(using q:     Quotes
  ): Expr[Unit] = {
    import q.reflect.*
    val helpers = new InterfaceCodecsHelpers[q.type]

    val implRepr = TypeRepr.of[I]
    val implSym  = implRepr.typeSymbol

    val WorkflowInterfaceSym = TypeRepr.of[workflowInterface].typeSymbol
    val ActivityInterfaceSym = TypeRepr.of[activityInterface].typeSymbol

    // If the type itself is annotated with @workflowInterface / @activityInterface, use it directly.
    // Otherwise walk ancestors for the closest annotated interface(s). Fall back to `I` itself if neither
    // the type nor any ancestor is annotated — the collector will then emit a clear error.
    val interfaceSyms: List[Symbol] = {
      val selfAnnotated =
        implSym.hasAnnotation(WorkflowInterfaceSym) || implSym.hasAnnotation(ActivityInterfaceSym)

      if (selfAnnotated) List(implSym)
      else {
        val ancestors = implRepr.baseClasses.filter { base =>
          base != implSym &&
          (base.hasAnnotation(WorkflowInterfaceSym) || base.hasAnnotation(ActivityInterfaceSym))
        }.distinct
        if (ancestors.nonEmpty) ancestors
        else List(implSym)
      }
    }

    val allCodecs = interfaceSyms.flatMap { ifaceSym =>
      helpers.collectInterfaceCodecs[I](
        interfaceSym = ifaceSym,
        context = "auto-register",
        strict = false
      )
    }
    // Dedupe across interfaces (two ifaces can share a type — e.g. common Input).
    val deduped = allCodecs.foldLeft(List.empty[helpers.CollectedCodec]) { (acc, c) =>
      if (acc.exists(_.tpe =:= c.tpe)) acc else acc :+ c
    }

    helpers.foldForeachRegistrations(registryOpt, deduped)
  }

  /** Implementation of the auto-registration call site for an activity ''implementation''. The type parameter `A` is
    * the concrete implementation class (e.g. `MyActivityImpl`) — we walk its supertype chain for
    * `@activityInterface`-annotated interfaces and register each one's codecs.
    *
    * Users occasionally declare one impl that implements two activity interfaces; each is walked.
    */
  def autoRegisterActivityImplImpl[A: Type](
    registryOpt: Expr[Option[CodecRegistry]]
  )(using q:     Quotes
  ): Expr[Unit] = {
    import q.reflect.*
    val helpers = new InterfaceCodecsHelpers[q.type]

    val implRepr             = TypeRepr.of[A]
    val implSym              = implRepr.typeSymbol
    val ActivityInterfaceSym = TypeRepr.of[activityInterface].typeSymbol

    // Find every @activityInterface-annotated supertype in A's base classes. If A is itself annotated
    // (rare but possible), we pick A too. Fall back to just A if no annotated ancestor exists — the
    // collector will then either find activity methods on A directly or skip silently (in non-strict mode).
    val activityInterfaces: List[Symbol] =
      if (implSym.hasAnnotation(ActivityInterfaceSym)) List(implSym)
      else
        implRepr.baseClasses.filter(_.hasAnnotation(ActivityInterfaceSym)) match {
          case Nil => List(implSym)
          case xs  => xs.distinct
        }

    val allCodecs = activityInterfaces.flatMap { ifaceSym =>
      helpers.collectInterfaceCodecs[A](
        interfaceSym = ifaceSym,
        context = "auto-register activity",
        strict = false
      )
    }
    // Dedupe across interfaces (two ifaces can share a type).
    val deduped = allCodecs.foldLeft(List.empty[helpers.CollectedCodec]) { (acc, c) =>
      if (acc.exists(_.tpe =:= c.tpe)) acc else acc :+ c
    }

    helpers.foldForeachRegistrations(registryOpt, deduped)
  }

  /** Class-scoped helpers used by the macro entry points. Holding them in a class parameterized on `Q <: Quotes` keeps
    * the `q.reflect.*` path-dependent types consistent across helper methods.
    */
  private class InterfaceCodecsHelpers[Q <: Quotes](using val q: Q) {
    import q.reflect.*

    /** A single codec to register: the Scala type and the summoned `ZTemporalCodec` expression. */
    case class CollectedCodec(tpe: TypeRepr, codecExpr: Expr[ZTemporalCodec[Any]])

    private val WorkflowMethodSym    = TypeRepr.of[workflowMethod].typeSymbol
    private val SignalMethodSym      = TypeRepr.of[signalMethod].typeSymbol
    private val QueryMethodSym       = TypeRepr.of[queryMethod].typeSymbol
    private val ActivityMethodSym    = TypeRepr.of[activityMethod].typeSymbol
    private val ActivityInterfaceSym = TypeRepr.of[activityInterface].typeSymbol
    private val WorkflowInterfaceSym = TypeRepr.of[workflowInterface].typeSymbol

    private val boundaryAnnotations = List(WorkflowMethodSym, SignalMethodSym, QueryMethodSym, ActivityMethodSym)

    /** Walk the interface `I` (or the symbol identified by `interfaceSym`, which must be compatible with `I`), collect
      * all boundary-method parameter and return types, summon a `ZTemporalCodec` for each, and return them as a list of
      * (type, codec-expression) pairs. `context` is used in error messages to tell the user which call site triggered
      * the failure.
      *
      * When `strict = true` (the default, used by `addInterface[I]`), a missing codec aborts compilation. When
      * `strict = false` (used by the auto-registration call sites), types without a summonable codec are silently
      * skipped — auto-reg must not fail compilation for types the user knowingly left uncodec-able (e.g. Scala 3
      * `Int | Null` erasure-test fixtures, newtype union tests). Those types erase to `Object` at runtime and the user
      * accepts that the payload path is not meant to handle them.
      */
    def collectInterfaceCodecs[I: Type](
      interfaceSym: Symbol,
      context:      String,
      strict:       Boolean = true
    ): List[CollectedCodec] = {
      val interfaceRepr = TypeRepr.of[I]
      val interfaceName = interfaceSym.fullName

      // On an @activityInterface, every public method is an activity (Java SDK convention). On a @workflowInterface,
      // only methods carrying a @workflowMethod/@signalMethod/@queryMethod annotation qualify.
      val isActivityInterface =
        interfaceSym.hasAnnotation(ActivityInterfaceSym) ||
          interfaceSym.typeRef.baseClasses.exists(_.hasAnnotation(ActivityInterfaceSym))

      // Consider inherited methods too: a sealed workflow like `SodaWorkflow extends ParameterizedWorkflow[Input]` has
      // its @workflowMethod inherited from the parent.
      val allMethods = interfaceSym.methodMembers ++ interfaceSym.declaredMethods

      val boundaryMethods = allMethods.filter { m =>
        if (isActivityInterface) {
          // Skip inherited Object methods — `toString`, `equals`, `hashCode`, etc. aren't activity endpoints.
          m.flags.is(Flags.Method) &&
          !m.flags.is(Flags.Synthetic) &&
          m.owner != defn.AnyClass &&
          m.owner != defn.ObjectClass
        } else {
          boundaryAnnotations.exists(m.hasAnnotation)
        }
      }.distinct

      if (boundaryMethods.isEmpty) {
        if (strict) {
          report.errorAndAbort(
            s"Interface $interfaceName has no methods annotated with @workflowMethod / @signalMethod / " +
              s"@queryMethod / @activityMethod, and it is not an @activityInterface whose methods are activities by " +
              s"default. `$context` against `$interfaceName` has nothing to register."
          )
        }
        // Non-strict: silent no-op. This happens when e.g. `ZWorker.addWorkflow[Impl]` falls through to the
        // impl class itself because no @workflowInterface ancestor was found — the user should have used the
        // interface type, but that's their bug to fix and not a compile-time show-stopper here.
        return Nil
      }

      // Collect every type that needs a codec: all parameter types + return type.
      // Use `interfaceRepr.memberType(m)` to resolve type parameters in the subclass's view — this makes a method
      // inherited from `Parent[User]` expose `User` as the parameter type rather than the abstract `A`.
      def collect(tpe: TypeRepr): List[TypeRepr] =
        tpe match {
          case MethodType(_, paramTypes, resultType) =>
            paramTypes ++ collect(resultType)
          case PolyType(_, _, resultType) =>
            collect(resultType)
          case ByNameType(underlying) =>
            collect(underlying)
          case other =>
            List(other)
        }

      // Also collect the type arguments of parameterized types — recursively. Every `List[Foo]` needs `Foo` registered
      // separately too, because the runtime encode path dispatches per-element using the element's own runtime class
      // (see `ZioJsonPayloadConverter#encodeContainer`). Without this recursion, a `List[Foo]` would have its own
      // parameterized-type codec in `byType` but no encoder for `Foo` in `byClass`, and per-element dispatch would
      // fall through to "not registered."
      def expandTypeArgs(tpe: TypeRepr): List[TypeRepr] = {
        val direct = tpe match {
          case AppliedType(_, args) => args.flatMap(expandTypeArgs)
          case _                    => Nil
        }
        tpe +: direct
      }

      // Redirect a concrete sealed subtype to its sealed ancestor so the registry only ever holds the parent codec
      // for types in a sealed hierarchy. Encoder lookup on a runtime class of `Soda` walks its supertype chain and
      // lands on the parent codec; decoder lookup on the inherited-method's upper-bound also lands on the parent
      // codec. Both sides converge on the same wrapped JSON shape (e.g. `{"Soda":{...}}`) — otherwise a directly
      // registered `Soda` codec would emit a flat `{"kind":"cola"}` that the parent-registered decoder rejects.
      //
      // Standard-library sealed traits (e.g. `scala.deriving.Mirror`, which is an ancestor of every case object's
      // mirror) are intentionally excluded — they are internal markers, not serialization targets.
      def promoteToSealedParent(tpe: TypeRepr): TypeRepr = {
        val selfSym    = tpe.typeSymbol
        val candidates = tpe.baseClasses.filter { base =>
          base != selfSym &&
          base.flags.is(Flags.Sealed) &&
          (base.flags.is(Flags.Trait) || base.flags.is(Flags.Abstract)) &&
          base != defn.AnyClass &&
          base != defn.ObjectClass &&
          base != defn.AnyValClass &&
          !isStdlibSymbol(base)
        }
        candidates.headOption match {
          case Some(parent) => tpe.baseType(parent)
          case None         => tpe
        }
      }

      def isStdlibSymbol(sym: Symbol): Boolean = {
        val name = sym.fullName
        name.startsWith("scala.") || name.startsWith("java.") || name.startsWith("javax.")
      }

      val typesNeedingCodecs: List[TypeRepr] = boundaryMethods
        .flatMap { m =>
          val resolved = interfaceRepr.memberType(m)
          collect(resolved).flatMap(expandTypeArgs).map(promoteToSealedParent)
        }
        .foldLeft(List.empty[TypeRepr]) { (acc, t) =>
          // Dedupe by semantic equivalence — avoids emitting duplicate register() calls.
          if (acc.exists(_ =:= t)) acc else acc :+ t
        }

      // For each type, summon its ZTemporalCodec. The `t.asType match { case '[tpe] => ... }` dance preserves the
      // type parameter so `register[tpe](...)` type-checks downstream.
      typesNeedingCodecs.flatMap { t =>
        t.asType match {
          case '[tpe] =>
            val codecType = TypeRepr.of[ZTemporalCodec[tpe]]
            Implicits.search(codecType) match {
              case success: ImplicitSearchSuccess =>
                // Cast the encoded tree to ZTemporalCodec[Any] for the heterogeneous list; the actual `register[tpe]`
                // call below recovers the narrow type via the pattern-matched `'[tpe]`. This is safe because the Scala
                // tree keeps its precise type — the `Any` is just the Scala-level container type.
                val codecExpr = success.tree.asExprOf[ZTemporalCodec[tpe]].asInstanceOf[Expr[ZTemporalCodec[Any]]]
                List(CollectedCodec(t, codecExpr))
              case failure: ImplicitSearchFailure =>
                if (strict) {
                  report.errorAndAbort(
                    s"Cannot $context codec for type `${t.show}` referenced in interface `$interfaceName`.\n" +
                      s"Reason: ${failure.explanation}\n" +
                      "Provide an implicit `ZTemporalCodec` for this type (typically via zio-json `JsonEncoder` + " +
                      "`JsonDecoder` on its companion), then re-try."
                  )
                } else {
                  // Non-strict: silently skip. The user either registered the codec elsewhere (explicit
                  // `.addInterface` / `.register`) or accepts that this type is uncodec-able (e.g. Scala 3 union
                  // types that erase to Object). A runtime "No ZTemporalCodec registered for …" will still fire
                  // at encode time if they actually try to serialize a value of the missing type.
                  Nil
                }
            }
        }
      }
    }

    /** Fold each collected codec into a chained `registry.register(...)` expression, returning the final registry
      * expression. Used by `addInterface[I]` which returns `CodecRegistry` for fluent chaining.
      */
    def foldRegistrations(
      registry: Expr[CodecRegistry],
      codecs:   List[CollectedCodec]
    ): Expr[CodecRegistry] = {
      codecs.foldLeft(registry) { (acc, collected) =>
        collected.tpe.asType match {
          case '[tpe] =>
            val narrowed = collected.codecExpr.asExprOf[ZTemporalCodec[tpe]]
            '{ $acc.register[tpe]($narrowed) }
        }
      }
    }

    /** Emit `registryOpt.foreach { r => r.register(c1); r.register(c2); ... }`. When `registryOpt` is `None` at
      * runtime, the `foreach` body never executes — that's the opt-out path for users who supplied their own
      * `DataConverter`.
      */
    def foldForeachRegistrations(
      registryOpt: Expr[Option[CodecRegistry]],
      codecs:      List[CollectedCodec]
    ): Expr[Unit] = {
      if (codecs.isEmpty) {
        '{ () }
      } else {
        '{
          $registryOpt.foreach { r =>
            ${
              // Build a block of r.register(...) statements inside the foreach.
              val stmts: List[Expr[Any]] = codecs.map { collected =>
                collected.tpe.asType match {
                  case '[tpe] =>
                    val narrowed = collected.codecExpr.asExprOf[ZTemporalCodec[tpe]]
                    '{ r.register[tpe]($narrowed) }
                }
              }
              Expr.block(stmts.init, stmts.last.asExprOf[Any])
            }
            ()
          }
        }
      }
    }
  }
}
