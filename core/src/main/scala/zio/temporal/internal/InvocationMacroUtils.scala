package zio.temporal.internal

import io.temporal.api.common.v1.WorkflowExecution
import zio.temporal.*
import zio.temporal.activity.{IsActivity, ZActivityStub}
import zio.temporal.schedules.ZScheduleStartWorkflowStub
import zio.temporal.workflow.*

import java.util.concurrent.CompletableFuture
import scala.annotation.tailrec
import scala.quoted.*
import scala.reflect.ClassTag

class InvocationMacroUtils[Q <: Quotes](using override val q: Q) extends MacroUtils[Q] {
  import q.reflect.*

  private val ActivityInterface = typeSymbolOf[activityInterface]
  private val WorkflowInterface = typeSymbolOf[workflowInterface]
  private val WorkflowMethod    = typeSymbolOf[workflowMethod]
  private val QueryMethod       = typeSymbolOf[queryMethod]
  private val SignalMethod      = typeSymbolOf[signalMethod]
  private val ActivityMethod    = typeSymbolOf[activityMethod]

  private val zworkflowStub              = TypeRepr.of[ZWorkflowStub]
  private val zchildWorkflowStub         = TypeRepr.of[ZChildWorkflowStub]
  private val zexternalWorkflowStub      = TypeRepr.of[ZExternalWorkflowStub]
  private val zworkflowContinueAsNewStub = TypeRepr.of[ZWorkflowContinueAsNewStub]
  private val zactivityStub              = TypeRepr.of[ZActivityStub]
  private val zscheduleStartWorkflowStub = TypeRepr.of[ZScheduleStartWorkflowStub]

  protected val IsWorkflowImplicitTC = TypeRepr.typeConstructorOf(classOf[IsWorkflow[Any]])
  protected val IsActivityImplicitC  = TypeRepr.typeConstructorOf(classOf[IsActivity[Any]])

  // Types that indicate erasure to Object (most general types in the type hierarchy)
  private val erasedToObjectTypes: List[Symbol] =
    List(typeSymbolOf[java.lang.Object], typeSymbolOf[Matchable], typeSymbolOf[Any])

  def betaReduceExpression[A: Type](f: Expr[A]): Expr[A] =
    Expr.betaReduce(f).asTerm.underlying.asExprOf[A]

  // NOTE: used assertWorkflow/assertActivity before, but it's too restrictive.
  // Checking the stubType instead allows usage of polymorphic workflow interfaces.
  // The fact that the stub was built guarantees that the workflow/signal/query/activity method was invoked on a valid stub
  def getMethodInvocation(tree: Term): MethodInvocation =
    tree match {
      case Inlined(_, _, body) =>
        getMethodInvocation(body)
      case Select(instance, methodName) =>
        MethodInvocation(instance, methodName, Nil)
      case Apply(Select(instance, methodName), args) =>
        MethodInvocation(instance, methodName, args)
      case TypeApply(inner, _) =>
        getMethodInvocation(inner)
      case Block(List(inner: Term), _) =>
        getMethodInvocation(inner)
      case _ => sys.error(s"Expected simple method invocation, got tree of class ${tree.getClass}: $tree")
    }

  case class MethodInvocation(
    instance:   Term,
    methodName: String,
    args: List[Term]) {

    val tpe: TypeRepr         = instance.tpe.widen
    private val unwrappedType = unwrapStub(instance.tpe.widen)

    def selectJavaReprOf[T: Type]: Expr[T] =
      selectMember[T]("toJava")

    def selectStubbedClass: Expr[Class[_]] =
      selectMember[Class[_]]("stubbedClass")

    def selectMember[T](name: String)(using Type[T]): Expr[T] =
      instance
        .select(instance.symbol.methodMember(name).head)
        .asExprOf[T]

    def getMethod(errorDetails: => String): MethodInfo =
      unwrappedType.typeSymbol
        .methodMember(methodName)
        .headOption
        .map(MethodInfo(methodName, _, args))
        .getOrElse(
          error(
            SharedCompileTimeMessages.methodNotFound(
              instance.tpe.show,
              methodName,
              errorDetails
            )
          )
        )
  }

  case class MethodInfo(name: String, symbol: Symbol, appliedArgs: List[Term]) {
    validateNoDefaultArgs()

    def assertWorkflowMethod(): Unit =
      if (!symbol.hasAnnotation(WorkflowMethod)) {
        error(SharedCompileTimeMessages.notWorkflowMethod(symbol.toString))
      }

    def assertSignalMethod(): Unit =
      if (!symbol.hasAnnotation(SignalMethod)) {
        error(SharedCompileTimeMessages.notSignalMethod(symbol.toString))
      }

    def assertQueryMethod(): Unit =
      if (!symbol.hasAnnotation(QueryMethod)) {
        error(SharedCompileTimeMessages.notQueryMethod(symbol.toString))
      }

    def argsExpr: Expr[List[Any]] = Expr.ofList(
      appliedArgs.map(_.asExprOf[Any])
    )

    def warnPossibleSerializationIssues(): Unit = {
      def getNonNullTypeFromUnion(t: TypeRepr): Option[TypeRepr] =
        t.dealias match {
          case OrType(left, right) =>
            if (left =:= TypeRepr.of[Null]) Some(right)
            else if (right =:= TypeRepr.of[Null]) Some(left)
            else None
          case _ => None
        }

      // Recursively dealias to handle nested type aliases and newtypes
      @tailrec
      def fullyDealias(tpe: TypeRepr): TypeRepr = {
        val dealiased = tpe.dealias
        if dealiased =:= tpe then dealiased
        else fullyDealias(dealiased)
      }

      // Check if a type would be erased to Object at runtime
      // Returns true if the type would be erased, but does NOT include exact Any/Object types
      def wouldBeErasedToObject(t: TypeRepr): Boolean = {
        // Try multiple ways to get the underlying type:
        // 1. Full dealiasing for type aliases
        // 2. Widen for refined types and opaque types
        val dealiasedType = fullyDealias(t)
        val widenedType   = dealiasedType.widen

        // Collect base classes from both dealiased and widened types
        // This handles both regular types and opaque types
        val baseClassesDealiased = dealiasedType.baseClasses
        val baseClassesWidened   = widenedType.baseClasses
        val allBaseClasses       = (baseClassesDealiased ++ baseClassesWidened).distinct

        // If all base classes are top-level types (Object/Any/Matchable), it would be erased to Object
        allBaseClasses.forall(erasedToObjectTypes.contains)
      }

      def findIssues(param: Symbol): Option[SharedCompileTimeMessages.TemporalMethodParameterIssue] = {
        param.tree match {
          case vd: ValDef =>
            val t = vd.tpt.tpe

            def findIssue(t: TypeRepr): Option[SharedCompileTimeMessages.TemporalMethodParameterIssue] = {
              // Check if the type IS java.lang.Object or Any directly
              if (t =:= TypeRepr.of[Any] || t =:= TypeRepr.of[java.lang.Object]) {
                Some(SharedCompileTimeMessages.TemporalMethodParameterIssue.isJavaLangObject(param.name.toString))
              } else if (wouldBeErasedToObject(t)) {
                Some(
                  SharedCompileTimeMessages.TemporalMethodParameterIssue.erasedToJavaLangObject(
                    name = param.name.toString,
                    tpe = t.show
                  )
                )
              } else None
            }

            getNonNullTypeFromUnion(t) match {
              case Some(t0) => findIssue(t0) // Union type with Null, aka `T0 | Null`
              case None     => findIssue(t)  // Not a union with Null
            }
          case other =>
            // The whole check is a warning, better not to fail the compilation
            warning(
              SharedCompileTimeMessages.unexpectedLibraryError(
                s"failed to check method's $name parameter ${param.name} type: " +
                  s"unexpected Symbol.tree:\n" +
                  s"class: ${other.getClass}\n" +
                  s"tree: $other"
              )
            )
            None
        }
      }

      val paramsWithIssues = symbol.paramSymss.flatMap(
        _.flatMap(findIssues)
      )
      for (issue <- paramsWithIssues) {
        warning(
          SharedCompileTimeMessages.temporalMethodParameterTypesHasIssue(
            method = name.toString,
            issue = issue
          )
        )
      }
    }

    private def validateNoDefaultArgs(): Unit = {
      val paramsWithDefault = symbol.paramSymss
        .flatMap(
          _.filter(_.flags is Flags.HasDefault)
        )
      if (paramsWithDefault.nonEmpty) {
        error(
          SharedCompileTimeMessages.defaultArgumentsNotSupported(
            paramsWithDefault.map(_.name)
          )
        )
      }
    }
  }

  def assertTypedWorkflowStub(workflow: TypeRepr, stubType: TypeRepr, method: String): TypeRepr = {
    workflow.dealias match {
      case AndType(stub, wf) =>
        if (!(stub =:= stubType))
          error(SharedCompileTimeMessages.usingNonStubOf(stubType.show, method, workflow.toString))
        wf
      case other =>
        error(
          SharedCompileTimeMessages.usingNonStubOf(stubType.show, method, other.show)
        )
    }
  }

  def assertTypedActivityStub(activity: TypeRepr, method: String): TypeRepr = {
    activity.dealias match {
      case AndType(stub, act) =>
        if (!(stub =:= zactivityStub))
          error(SharedCompileTimeMessages.usingNonStubOf("ZActivityStub", method, activity.show))
        else act
      case other =>
        error(
          SharedCompileTimeMessages.usingNonStubOf("ZActivityStub", method, other.show)
        )
    }
  }

  def isWorkflow(sym: Symbol): Boolean =
    sym.hasAnnotation(WorkflowInterface)

  def unwrapStub(stub: TypeRepr): TypeRepr = {
    stub.dealias match {
      case AndType(stub, wrapped)
          if stub =:= zworkflowStub ||
            stub =:= zchildWorkflowStub ||
            stub =:= zexternalWorkflowStub ||
            stub =:= zworkflowContinueAsNewStub ||
            stub =:= zscheduleStartWorkflowStub ||
            stub =:= zactivityStub =>
        wrapped
      case other => other
    }
  }

  def assertWorkflow(workflow: TypeRepr, isFromImplicit: Boolean): TypeRepr = {
    val tpe = unwrapStub(workflow)
    if (isWorkflow(tpe.typeSymbol) || isWorkflowImplicitProvided(tpe, isFromImplicit)) {
      tpe
    } else {
      error(SharedCompileTimeMessages.notWorkflow(tpe.show))
    }
  }

  private def isWorkflowImplicitProvided(workflow: TypeRepr, isFromImplicit: Boolean): Boolean = {
    // Don't infer implicit IsWorkflow in case that the IsWorkflow derivation
    !isFromImplicit && {
      Implicits.search(IsWorkflowImplicitTC.appliedTo(workflow)) match {
        case _: ImplicitSearchSuccess => true
        case _: ImplicitSearchFailure => false
      }
    }
  }

  def extendsWorkflow(tpe: TypeRepr): Boolean =
    isWorkflow(tpe.typeSymbol) || tpe.baseClasses.exists(isWorkflow)

  def assertExtendsWorkflow(workflow: TypeRepr): Unit =
    if (!extendsWorkflow(workflow)) {
      error(SharedCompileTimeMessages.notWorkflow(workflow.show))
    }

  def isActivity(sym: Symbol): Boolean =
    sym.hasAnnotation(ActivityInterface)

  def assertActivity(activity: TypeRepr, isFromImplicit: Boolean): TypeRepr = {
    val tpe = activity.dealias match {
      case AndType(_, act) => act
      case other           => other
    }
    if (isActivity(tpe.typeSymbol) || isActivityImplicitProvided(tpe, isFromImplicit)) {
      tpe
    } else {
      error(SharedCompileTimeMessages.notActivity(tpe.show))
    }
  }

  private def isActivityImplicitProvided(activity: TypeRepr, isFromImplicit: Boolean): Boolean = {
    // Don't infer implicit IsActivity in case that the IsActivity derivation
    !isFromImplicit && {
      Implicits.search(IsActivityImplicitC.appliedTo(activity)) match {
        case _: ImplicitSearchSuccess => true
        case _: ImplicitSearchFailure => false
      }
    }
  }

  def extendsActivity(tpe: TypeRepr): Boolean =
    isActivity(tpe.typeSymbol) || tpe.baseClasses.exists(isActivity)

  def assertExtendsActivity(activity: TypeRepr): Unit =
    if (!extendsActivity(activity)) {
      error(SharedCompileTimeMessages.notActivity(activity.show))
    }

  def getQueryName(method: Symbol): String = {
    val ann = method.getAnnotation(QueryMethod)
    ann match {
      case Some(Apply(Select(New(_), _), List(NamedArg(_, Literal(StringConstant(name)))))) =>
        name
      case _ => method.name
    }
  }

  def getSignalName(method: Symbol): String = {
    val ann = method.getAnnotation(SignalMethod)
    ann match {
      case Some(Apply(Select(New(_), _), List(NamedArg(_, Literal(StringConstant(name)))))) =>
        name
      case _ => method.name
    }
  }

  private def typeSymbolOf[A: Type]: Symbol =
    TypeRepr.of[A].dealias.typeSymbol
}
