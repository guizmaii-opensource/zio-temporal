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
  */
object InterfaceCodecsMacros {

  /** Implementation of `CodecRegistry#addInterface[I]`. Walks `I`, summons codecs, emits a chained
    * `registry.register(...)` for each.
    */
  def addInterfaceImpl[I: Type](registry: Expr[CodecRegistry])(using q: Quotes): Expr[CodecRegistry] = {
    import q.reflect.*

    val interfaceRepr = TypeRepr.of[I]
    val interfaceSym  = interfaceRepr.typeSymbol
    val interfaceName = interfaceRepr.show

    val WorkflowMethodSym    = TypeRepr.of[workflowMethod].typeSymbol
    val SignalMethodSym      = TypeRepr.of[signalMethod].typeSymbol
    val QueryMethodSym       = TypeRepr.of[queryMethod].typeSymbol
    val ActivityMethodSym    = TypeRepr.of[activityMethod].typeSymbol
    val ActivityInterfaceSym = TypeRepr.of[activityInterface].typeSymbol
    val WorkflowInterfaceSym = TypeRepr.of[workflowInterface].typeSymbol

    val boundaryAnnotations = List(WorkflowMethodSym, SignalMethodSym, QueryMethodSym, ActivityMethodSym)

    // On an @activityInterface, every public method is an activity (Java SDK convention). On a @workflowInterface,
    // only methods carrying a @workflowMethod/@signalMethod/@queryMethod annotation qualify.
    val isActivityInterface =
      interfaceSym.hasAnnotation(ActivityInterfaceSym) ||
        interfaceRepr.baseClasses.exists(_.hasAnnotation(ActivityInterfaceSym))

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
      report.errorAndAbort(
        s"Interface $interfaceName has no methods annotated with @workflowMethod / @signalMethod / " +
          s"@queryMethod / @activityMethod, and it is not an @activityInterface whose methods are activities by " +
          s"default. `addInterface[$interfaceName]` has nothing to register."
      )
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

    val typesNeedingCodecs: List[TypeRepr] = boundaryMethods
      .flatMap { m =>
        val resolved = interfaceRepr.memberType(m)
        collect(resolved).flatMap(expandTypeArgs)
      }
      .foldLeft(List.empty[TypeRepr]) { (acc, t) =>
        // Dedupe by semantic equivalence — avoids emitting duplicate register() calls.
        if (acc.exists(_ =:= t)) acc else acc :+ t
      }

    // For each type, summon its ZTemporalCodec and fold a register() call onto the accumulating registry expression.
    // The `t.asType match { case '[tpe] => ... }` dance preserves the type parameter so `register[tpe](...)` type-checks.
    typesNeedingCodecs.foldLeft(registry) { (acc, t) =>
      t.asType match {
        case '[tpe] =>
          val codecType = TypeRepr.of[ZTemporalCodec[tpe]]
          Implicits.search(codecType) match {
            case success: ImplicitSearchSuccess =>
              val codecExpr = success.tree.asExprOf[ZTemporalCodec[tpe]]
              '{ $acc.register[tpe]($codecExpr) }
            case failure: ImplicitSearchFailure =>
              report.errorAndAbort(
                s"Cannot auto-register codec for type `${t.show}` referenced in interface `$interfaceName`.\n" +
                  s"Reason: ${failure.explanation}\n" +
                  "Provide an implicit `ZTemporalCodec` for this type (typically via zio-json `JsonEncoder` + " +
                  "`JsonDecoder` on its companion), then re-try `addInterface`."
              )
          }
      }
    }
  }
}
