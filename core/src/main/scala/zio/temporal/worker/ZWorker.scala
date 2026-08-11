package zio.temporal.worker

import zio._
import zio.temporal.internal.ClassTagUtils
import io.temporal.worker.Worker
import zio.temporal.activity.{ExtendsActivity, IsActivity, ZActivityImplementationObject}
import zio.temporal.json.CodecRegistry
import zio.temporal.workflow.{
  ExtendsWorkflow,
  HasPublicNullaryConstructor,
  IsConcreteClass,
  IsWorkflow,
  ZWorkflowImplementationClass
}
import io.temporal.worker.WorkerFactory

import scala.reflect.ClassTag

/** Hosts activity and workflow implementations. Uses long poll to receive activity and workflow tasks and processes
  * them in a correspondent thread pool.
  *
  * The `codecRegistry` carried here is the ''same'' `CodecRegistry` owned by this worker's `ZWorkflowClientOptions`.
  * When `None`, the user supplied a custom `DataConverter` via `withDataConverter(raw)` and auto-registration is a
  * silent no-op. When `Some`, the auto-registration call sites (`addWorkflow[I]` / `addActivityImplementation(impl)` /
  * …) populate the registry at invocation time so users don't need to chain `.addInterface[...]` by hand on the
  * options.
  */
class ZWorker private[zio] (
  val toJava: Worker,
  private[zio] val codecRegistry: Option[CodecRegistry]) {

  /** Secondary constructor retained for call sites that don't have a registry reference. */
  private[zio] def this(toJava: Worker) = this(toJava, None)

  def taskQueue: String =
    toJava.getTaskQueue

  def isSuspended: UIO[Boolean] =
    ZIO.succeed(toJava.isSuspended)

  def suspendPolling: UIO[Unit] =
    ZIO.succeedBlocking(toJava.suspendPolling())

  def resumePolling: UIO[Unit] =
    ZIO.succeedBlocking(toJava.resumePolling())

  override def toString: String =
    toJava.toString
      .replace("Worker", "ZWorker")
      .replace("WorkerOptions", "ZWorkerOptions")
      .replace("{", "(")
      .replace("}", ")")

  /** Adds workflow to this worker.
    *
    * Auto-registration: the codecs for every parameter and return type of `I`'s `@workflowMethod` / `@signalMethod` /
    * `@queryMethod` methods are registered into this worker's `CodecRegistry` at compile time. This eliminates the need
    * to chain `.addInterface[I]` on `ZWorkflowClientOptions.withCodecRegistry(...)`.
    *
    * Opt-out: when the user supplied a custom `DataConverter` via `withDataConverter(raw)`, the registry is `None` and
    * this auto-registration is a silent no-op — the foreign converter is trusted to handle serialization.
    */
  inline def addWorkflow[I: ExtendsWorkflow]: ZWorker.AddWorkflowDsl[I] = {
    zio.temporal.json.CodecRegistry.autoRegisterInterface[I](codecRegistry)
    ZWorker.buildAddWorkflowDsl[I](this, ZWorkflowImplementationOptions.default)
  }

  /** @see
    *   [[addWorkflowImplementations]]
    */
  def addWorkflowImplementations(
    workflowImplementationClass: ZWorkflowImplementationClass[_],
    moreClasses:                 ZWorkflowImplementationClass[_]*
  ): UIO[ZWorker] =
    addWorkflowImplementations((workflowImplementationClass +: moreClasses).toList)

  /** Registers workflow implementation classes with a worker. Can be called multiple times to add more types. A
    * workflow implementation class must implement at least one interface with a method annotated with
    * [[zio.temporal.workflowMethod]]. By default, the short name of the interface is used as a workflow type that this
    * worker supports.
    *
    * <p>Implementations that share a worker must implement different interfaces as a workflow type is identified by the
    * workflow interface, not by the implementation.
    *
    * @throws io.temporal.worker.TypeAlreadyRegisteredException
    *   if one of the workflow types is already registered
    */
  def addWorkflowImplementations(
    workflowImplementationClasses: List[ZWorkflowImplementationClass[_]]
  ): UIO[ZWorker] = ZIO.succeed {
    if (workflowImplementationClasses.nonEmpty) {
      toJava.registerWorkflowImplementationTypes(
        workflowImplementationClasses.map(_.runtimeClass): _*
      )
    }
    this
  }

  /** @see
    *   [[addWorkflowImplementations]]
    */
  def addWorkflowImplementations(
    options:                     ZWorkflowImplementationOptions,
    workflowImplementationClass: ZWorkflowImplementationClass[_],
    moreClasses:                 ZWorkflowImplementationClass[_]*
  ): UIO[ZWorker] =
    addWorkflowImplementations(options, (workflowImplementationClass +: moreClasses).toList)

  /** @see
    *   [[addWorkflowImplementations]]
    */
  def addWorkflowImplementations(
    options:                       ZWorkflowImplementationOptions,
    workflowImplementationClasses: List[ZWorkflowImplementationClass[_]]
  ): UIO[ZWorker] = ZIO.succeed {
    toJava.registerWorkflowImplementationTypes(
      options.toJava,
      workflowImplementationClasses.map(_.runtimeClass): _*
    )
    this
  }

  /** Registers activity implementation objects with a worker. An implementation object can implement one or more
    * activity types.
    *
    * Auto-registration: at compile time, the macro walks `A`'s base classes for every `@activityInterface`- annotated
    * ancestor and registers each interface's codecs into this worker's `CodecRegistry`. This eliminates the need to
    * chain `.addInterface[A]` on `ZWorkflowClientOptions.withCodecRegistry(...)`.
    *
    * Opt-out: when the user supplied a custom `DataConverter` via `withDataConverter(raw)`, the registry is `None` and
    * this auto-registration is a silent no-op.
    *
    * @see
    *   [[Worker#registerActivitiesImplementations]]
    */
  inline def addActivityImplementation[A <: AnyRef: ExtendsActivity](activity: A): UIO[ZWorker] = {
    zio.temporal.json.CodecRegistry.autoRegisterActivityImpl[A](codecRegistry)
    ZIO.succeed {
      toJava.registerActivitiesImplementations(activity)
      this
    }
  }

  /** @see
    *   [[addActivityImplementations]]
    */
  def addActivityImplementations(
    activityImplementationObject: ZActivityImplementationObject[_],
    moreObjects:                  ZActivityImplementationObject[_]*
  ): UIO[ZWorker] =
    addActivityImplementations((activityImplementationObject +: moreObjects).toList)

  /** Register activity implementation objects with a worker. An implementation object can implement one or more
    * activity types.
    *
    * <p>An activity implementation object must implement at least one interface annotated with
    * [[zio.temporal.activityInterface]]. Each method of the annotated interface becomes an activity type.
    *
    * <p>Implementations that share a worker must implement different interfaces as an activity type is identified by
    * the activity interface, not by the implementation.
    *
    * Auto-registration: each `ZActivityImplementationObject` carries a `registerCodecs` thunk captured at its
    * construction time, so the element types are not lost by the `List[_]` boundary. This method invokes every thunk
    * against this worker's registry before handing the impls to the Java SDK. Opt-out (`withDataConverter(raw)` →
    * `codecRegistry = None`) is a silent no-op.
    */
  def addActivityImplementations(
    activityImplementationObjects: List[ZActivityImplementationObject[_]]
  ): UIO[ZWorker] = ZIO.succeed {
    activityImplementationObjects.foreach(_.registerCodecs(codecRegistry))
    // safe to case as ZActivityImplementationObject type parameter is <: AnyRef
    // Note: cast is needed only for Scala 2.12
    toJava.registerActivitiesImplementations(activityImplementationObjects.map(_.value.asInstanceOf[AnyRef]): _*)
    this
  }

  /** Registers activity implementation objects with a worker. An implementation object can implement one or more
    * activity types.
    *
    * Auto-registration: same contract as [[addActivityImplementation]].
    *
    * @see
    *   [[Worker#registerActivitiesImplementations]]
    */
  inline def addActivityImplementationService[A <: AnyRef: ExtendsActivity: Tag]: URIO[A, ZWorker] = {
    ZIO.serviceWithZIO[A] { activity =>
      addActivityImplementation[A](activity)
    }
  }
}

object ZWorker {

  type Add[+LowerR, -UpperR] = ZIOAspect[LowerR, UpperR, Nothing, Any, ZWorker, ZWorker]

  /** Internal builder for [[AddWorkflowDsl]] — needed because Scala 3 forbids inline methods from calling
    * `private[zio]` constructors directly. Invoked by the inline `ZWorker#addWorkflow[I]`.
    */
  @zio.temporal.internalApi
  def buildAddWorkflowDsl[I](worker: ZWorker, options: ZWorkflowImplementationOptions): AddWorkflowDsl[I] =
    new AddWorkflowDsl[I](worker, options)

  /** Internal helper used by the `inline` aspect-construction methods to avoid duplicating the anonymous-class
    * definition at every inline call site (a Scala 3 inline hygiene issue, [E197]). The thunk is called inside the
    * aspect's `flatMap` and is expected to return the worker (after any side-effectful registration).
    */
  @zio.temporal.internalApi
  def buildWorkerAspect(thunk: ZWorker => UIO[ZWorker]): ZWorker.Add[Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, ZWorker, ZWorker] {
      override def apply[R >: Nothing <: Any, E >: Nothing <: Any, A >: ZWorker <: ZWorker](
        zio:            ZIO[R, E, A]
      )(implicit trace: Trace
      ): ZIO[R, E, A] =
        zio.flatMap(thunk)
    }

  /** Same as [[buildWorkerAspect]] but with a custom lower-bound environment type `Activity` — used by
    * `addActivityImplementationService` which requires `Activity` in the ZIO environment.
    */
  @zio.temporal.internalApi
  def buildWorkerAspectWithEnv[Activity](
    thunk: ZWorker => URIO[Activity, ZWorker]
  ): ZWorker.Add[Nothing, Activity] =
    new ZIOAspect[Nothing, Activity, Nothing, Any, ZWorker, ZWorker] {
      override def apply[R >: Nothing <: Activity, E >: Nothing <: Any, A >: ZWorker <: ZWorker](
        zio:            ZIO[R, E, A]
      )(implicit trace: Trace
      ): ZIO[R, E, A] =
        zio.flatMap(thunk)
    }

  /** Same as [[buildWorkerAspect]] but carries an extra `R0 with Scope` environment type — used by the layer-based
    * activity aspect methods.
    */
  @zio.temporal.internalApi
  def buildScopedWorkerAspect[R0, E0](
    thunk: ZWorker => ZIO[R0 with Scope, E0, ZWorker]
  ): ZIOAspect[Nothing, R0 with Scope, E0, Any, ZWorker, ZWorker] =
    new ZIOAspect[Nothing, R0 with Scope, E0, Any, ZWorker, ZWorker] {
      override def apply[R >: Nothing <: R0 with Scope, E >: E0 <: Any, A >: ZWorker <: ZWorker](
        zio:            ZIO[R, E, A]
      )(implicit trace: Trace
      ): ZIO[R, E, A] =
        zio.flatMap(thunk)
    }

  /** Adds workflow to this worker
    */
  def addWorkflow[I: ExtendsWorkflow]: ZWorker.AddWorkflowAspectDsl[I] =
    new AddWorkflowAspectDsl[I](options = ZWorkflowImplementationOptions.default)

  /** @see
    *   [[ZWorker.addWorkflowImplementations]]
    */
  def addWorkflowImplementations(
    workflowImplementationClass: ZWorkflowImplementationClass[_],
    moreClasses:                 ZWorkflowImplementationClass[_]*
  ): ZWorker.Add[Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, ZWorker, ZWorker] {
      override def apply[R >: Nothing <: Any, E >: Nothing <: Any, A >: ZWorker <: ZWorker](
        zio:            ZIO[R, E, A]
      )(implicit trace: Trace
      ): ZIO[R, E, A] =
        zio.flatMap(_.addWorkflowImplementations(workflowImplementationClass, moreClasses: _*))
    }

  /** @see
    *   [[ZWorker.addWorkflowImplementations]]
    */
  def addWorkflowImplementations(
    workflowImplementationClasses: List[ZWorkflowImplementationClass[_]]
  ): ZWorker.Add[Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, ZWorker, ZWorker] {
      override def apply[R >: Nothing <: Any, E >: Nothing <: Any, A >: ZWorker <: ZWorker](
        zio:            ZIO[R, E, A]
      )(implicit trace: Trace
      ): ZIO[R, E, A] =
        zio.flatMap(_.addWorkflowImplementations(workflowImplementationClasses))
    }

  /** @see
    *   [[addWorkflowImplementations]]
    */
  def addWorkflowImplementations(
    options:                     ZWorkflowImplementationOptions,
    workflowImplementationClass: ZWorkflowImplementationClass[_],
    moreClasses:                 ZWorkflowImplementationClass[_]*
  ): ZWorker.Add[Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, ZWorker, ZWorker] {
      override def apply[R >: Nothing <: Any, E >: Nothing <: Any, A >: ZWorker <: ZWorker](
        zio:            ZIO[R, E, A]
      )(implicit trace: Trace
      ): ZIO[R, E, A] =
        zio.flatMap(_.addWorkflowImplementations(options, workflowImplementationClass, moreClasses: _*))
    }

  /** @see
    *   [[addWorkflowImplementations]]
    */
  def addWorkflowImplementations(
    options:                       ZWorkflowImplementationOptions,
    workflowImplementationClasses: List[ZWorkflowImplementationClass[_]]
  ): ZWorker.Add[Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, ZWorker, ZWorker] {
      override def apply[R >: Nothing <: Any, E >: Nothing <: Any, A >: ZWorker <: ZWorker](
        zio:            ZIO[R, E, A]
      )(implicit trace: Trace
      ): ZIO[R, E, A] =
        zio.flatMap(_.addWorkflowImplementations(options, workflowImplementationClasses))
    }

  /** Aspect-side entry point for `addActivityImplementation`.
    *
    * Auto-registration: `inline` so the macro expansion on the inner `worker.addActivityImplementation[Activity]`
    * happens at the user's concrete call site where `Activity` is known.
    */
  inline def addActivityImplementation[Activity <: AnyRef: ExtendsActivity](
    activity: Activity
  ): ZWorker.Add[Nothing, Any] =
    buildWorkerAspect(_.addActivityImplementation[Activity](activity))

  /** @see
    *   [[ZWorker.addActivityImplementations]]
    */
  def addActivityImplementations(
    activityImplementationObject: ZActivityImplementationObject[_],
    moreObjects:                  ZActivityImplementationObject[_]*
  ): ZWorker.Add[Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, ZWorker, ZWorker] {
      override def apply[R >: Nothing <: Any, E >: Nothing <: Any, A >: ZWorker <: ZWorker](
        zio:            ZIO[R, E, A]
      )(implicit trace: Trace
      ): ZIO[R, E, A] =
        zio.flatMap(_.addActivityImplementations(activityImplementationObject, moreObjects: _*))
    }

  /** @see
    *   [[ZWorker.addActivityImplementations]]
    */
  def addActivityImplementations(
    activityImplementationObjects: List[ZActivityImplementationObject[_]]
  ): ZWorker.Add[Nothing, Any] =
    new ZIOAspect[Nothing, Any, Nothing, Any, ZWorker, ZWorker] {
      override def apply[R >: Nothing <: Any, E >: Nothing <: Any, A >: ZWorker <: ZWorker](
        zio:            ZIO[R, E, A]
      )(implicit trace: Trace
      ): ZIO[R, E, A] =
        zio.flatMap(_.addActivityImplementations(activityImplementationObjects))
    }

  /** Adds activities from the given [[ZLayer]]
    *
    * @param activitiesLayer
    *   the list of activity implementation objects as a [[ZLayer]]
    */
  def addActivityImplementationsLayer[R0, E0](
    activitiesLayer: ZLayer[R0, E0, List[ZActivityImplementationObject[_]]]
  ): ZIOAspect[Nothing, R0 with Scope, E0, Any, ZWorker, ZWorker] = {
    new ZIOAspect[Nothing, R0 with Scope, E0, Any, ZWorker, ZWorker] {
      override def apply[R >: Nothing <: R0 with Scope, E >: E0 <: Any, A >: ZWorker <: ZWorker](
        zio:            ZIO[R, E, A]
      )(implicit trace: Trace
      ): ZIO[R, E, A] = {
        zio.flatMap { worker =>
          activitiesLayer.build.flatMap { env =>
            worker.addActivityImplementations(env.get[List[ZActivityImplementationObject[_]]])
          }
        }
      }
    }
  }

  /** Adds activity from the ZIO environment.
    *
    * Auto-registration: `inline` so the inner `addActivityImplementation[Activity]` macro sees a concrete `Activity`.
    * Same opt-out semantics as the instance-side method.
    */
  inline def addActivityImplementationService[Activity <: AnyRef: ExtendsActivity: Tag]
    : ZWorker.Add[Nothing, Activity] =
    buildWorkerAspectWithEnv[Activity](_.addActivityImplementationService[Activity])

  /** Adds activity from the given [[ZLayer]]
    *
    * Auto-registration: `inline`, same reason as [[addActivityImplementationService]].
    *
    * @param layer
    *   the activity implementation object as a [[ZLayer]]
    */
  inline def addActivityImplementationLayer[R0, Activity <: AnyRef: ExtendsActivity: Tag, E0](
    layer: ZLayer[R0, E0, Activity]
  ): ZIOAspect[Nothing, R0 with Scope, E0, Any, ZWorker, ZWorker] =
    buildScopedWorkerAspect[R0, E0] { worker =>
      layer.build.flatMap { env =>
        worker.addActivityImplementation[Activity](env.get[Activity])
      }
    }

  /** Allows building workers using [[ZIOAspect]]
    */
  final class AddWorkflowAspectDsl[I: ExtendsWorkflow] private[zio] (
    options: ZWorkflowImplementationOptions) {

    /** Specifies workflow implementation options for the worker
      *
      * @param value
      *   custom workflow implementation options for a worker
      */
    def withOptions(value: ZWorkflowImplementationOptions): AddWorkflowAspectDsl[I] =
      new AddWorkflowAspectDsl[I](value)

    /** Registers workflow implementation classes with a worker. Can be called multiple times to add more types.
      *
      * Auto-registration: the aspect's public call site (`ZWorker.addWorkflow[I].fromClass`) always has a concrete `I`,
      * so we auto-register `I`'s codecs inside the aspect's `flatMap` — where the worker is available — by going
      * through the (inline) `worker.addWorkflow[I]` method. Making `fromClass` itself inline keeps the macro expansion
      * visible at the user's concrete call site instead of inside this generic class's compilation.
      *
      * @param ctg
      *   workflow interface class tag
      * @see
      *   [[Worker#registerWorkflowImplementationTypes]]
      */
    inline def fromClass(implicit
      ctg:                         ClassTag[I],
      isConcreteClass:             IsConcreteClass[I],
      hasPublicNullaryConstructor: HasPublicNullaryConstructor[I]
    ): ZWorker.Add[Nothing, Any] = {
      val capturedOptions = options
      ZWorker.buildWorkerAspect(_.addWorkflow[I].withOptions(capturedOptions).fromClass)
    }

    /** Registers workflow implementation classes with a worker. Can be called multiple times to add more types.
      *
      * @param cls
      *   workflow interface class tag
      * @see
      *   [[Worker#registerWorkflowImplementationTypes]]
      */
    inline def fromClass(
      cls: Class[I]
    )(implicit
      isConcreteClass:             IsConcreteClass[I],
      hasPublicNullaryConstructor: HasPublicNullaryConstructor[I]
    ): ZWorker.Add[Nothing, Any] = {
      val capturedOptions = options
      ZWorker.buildWorkerAspect(_.addWorkflow[I].withOptions(capturedOptions).fromClass(cls))
    }

    /** Configures a factory to use when an instance of a workflow implementation is created. The only valid use for
      * this method is unit testing, specifically to instantiate mocks that implement child workflows. An example of
      * mocking a child workflow:
      *
      * @tparam Workflow
      *   workflow interface implementation
      * @param f
      *   should create a workflow implementation
      * @param ctg
      *   workflow interface class tag
      * @see
      *   [[Worker#addWorkflowImplementationFactory]]
      */
    inline def from[Workflow <: I](inline f: => Workflow)(implicit ctg: ClassTag[I]): ZWorker.Add[Nothing, Any] = {
      val capturedOptions = options
      ZWorker.buildWorkerAspect(_.addWorkflow[I].withOptions(capturedOptions).from(f))
    }

    /** Configures a factory to use when an instance of a workflow implementation is created. The only valid use for
      * this method is unit testing, specifically to instantiate mocks that implement child workflows. An example of
      * mocking a child workflow:
      *
      * @param cls
      *   workflow interface class
      * @param f
      *   should create a workflow implementation
      * @see
      *   [[Worker#addWorkflowImplementationFactory]]
      */
    inline def from(cls: Class[I], f: () => I): ZWorker.Add[Nothing, Any] = {
      val capturedOptions = options
      ZWorker.buildWorkerAspect(_.addWorkflow[I].withOptions(capturedOptions).from(cls, f))
    }
  }

  /** Allows building workers
    */
  final class AddWorkflowDsl[I] private[zio] (worker: ZWorker, options: ZWorkflowImplementationOptions) {

    /** Specifies workflow implementation options for the worker
      *
      * @param value
      *   custom workflow implementation options for a worker
      */
    def withOptions(value: ZWorkflowImplementationOptions): AddWorkflowDsl[I] =
      new AddWorkflowDsl[I](worker, value)

    /** Registers workflow implementation classes with a worker. Can be called multiple times to add more types.
      *
      * @param ctg
      *   workflow interface class tag
      * @see
      *   [[Worker#registerWorkflowImplementationTypes]]
      */
    def fromClass(implicit
      ctg:                         ClassTag[I],
      isConcreteClass:             IsConcreteClass[I],
      hasPublicNullaryConstructor: HasPublicNullaryConstructor[I]
    ): UIO[ZWorker] =
      fromClass(ClassTagUtils.classOf[I])

    /** Registers workflow implementation classes with a worker. Can be called multiple times to add more types.
      * @param cls
      *   workflow interface class tag
      * @see
      *   [[Worker#registerWorkflowImplementationTypes]]
      */
    def fromClass(
      cls: Class[I]
    )(implicit
      isConcreteClass:             IsConcreteClass[I],
      hasPublicNullaryConstructor: HasPublicNullaryConstructor[I]
    ): UIO[ZWorker] = {
      ZIO.succeed {
        worker.toJava.registerWorkflowImplementationTypes(options.toJava, cls)
        worker
      }
    }

    /** Configures a factory to use when an instance of a workflow implementation is created. The only valid use for
      * this method is unit testing, specifically to instantiate mocks that implement child workflows. An example of
      * mocking a child workflow:
      *
      * @tparam A
      *   workflow interface implementation
      * @param f
      *   should create a workflow implementation
      * @param ctg
      *   workflow interface class tag
      * @see
      *   [[Worker#addWorkflowImplementationFactory]]
      */
    def from[A <: I](f: => A)(implicit ctg: ClassTag[I]): UIO[ZWorker] =
      from(ClassTagUtils.classOf[I], () => f)

    /** Configures a factory to use when an instance of a workflow implementation is created. The only valid use for
      * this method is unit testing, specifically to instantiate mocks that implement child workflows. An example of
      * mocking a child workflow:
      *
      * @param cls
      *   workflow interface class
      * @param f
      *   should create a workflow implementation
      * @see
      *   [[Worker#addWorkflowImplementationFactory]]
      */
    def from(cls: Class[I], f: () => I): UIO[ZWorker] =
      ZIO.succeed {
        worker.toJava.registerWorkflowImplementationFactory[I](cls, () => f(), options.toJava)
        worker
      }
  }
}
