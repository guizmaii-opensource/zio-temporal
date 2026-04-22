package zio.temporal

import io.temporal.common.converter.{DataConverter, PayloadConverter}
import io.temporal.api.common.v1.Payload
import io.temporal.common.converter.DefaultDataConverter
import zio._
import zio.logging.backend.SLF4J
import zio.temporal.activity.{ZActivityImplementationObject, ZActivityOptions, ZActivityRunOptions}
import zio.temporal.fixture._
import zio.temporal.json.{CodecRegistry, ZTemporalCodec, ZioJsonDataConverter}
import zio.temporal.testkit._
import zio.temporal.worker._
import zio.temporal.workflow._
import zio.test._

/** End-to-end integration coverage for the auto-registration feature. Contrast with [[BaseTemporalSpec]] which wires
  * every spec with the shared [[FixtureCodecRegistry.all]] — here we intentionally wire clients with an empty
  * `CodecRegistry` to verify that the auto-reg call sites fill it in correctly at worker / stub-creation time.
  */
object AutoRegistrationSpec extends ZIOSpecDefault {
  override val bootstrap: ZLayer[Any, Any, TestEnvironment] =
    testEnvironment ++ Runtime.removeDefaultLoggers ++ SLF4J.slf4j

  /** A `ZWorkflowClientOptions` with an empty registry — auto-reg must populate it via the call sites. */
  private val emptyRegistryClientOptions: ULayer[ZWorkflowClientOptions] =
    ZWorkflowClientOptions.make.orDie

  private val emptyRegistryWorkflowEnv: ULayer[ZTestWorkflowEnvironment[Any]] =
    ZLayer.make[ZTestWorkflowEnvironment[Any]](
      emptyRegistryClientOptions,
      ZWorkerFactoryOptions.make.orDie,
      ZTestEnvironmentOptions.make,
      ZTestWorkflowEnvironment.make[Any]
    )

  override val spec = suite("Auto-registration of codecs at worker/stub sites")(
    test("workflow auto-registers its interface codecs via addWorkflow[I] and newWorkflowStub[I]") {
      val taskQueue = "auto-reg-workflow-queue"

      for {
        workflowId <- ZIO.randomWith(_.nextUUID)
        // No manual addInterface — auto-reg happens on addWorkflow and newWorkflowStub.
        _ <- ZTestWorkflowEnvironment.newWorker(taskQueue) @@
               ZWorker.addWorkflow[SampleWorkflowImpl].fromClass
        _        <- ZTestWorkflowEnvironment.setup()
        workflow <- ZTestWorkflowEnvironment.newWorkflowStub[SampleWorkflow](
                      ZWorkflowOptions
                        .withWorkflowId(workflowId.toString)
                        .withTaskQueue(taskQueue)
                        .withWorkflowRunTimeout(10.second)
                    )
        result <- ZWorkflowStub.execute(workflow.echo("auto-reg"))
      } yield assertTrue(result == "auto-reg")
    }.provideSomeLayer[Scope](emptyRegistryWorkflowEnv) @@ TestAspect.withLiveClock,
    test("activity auto-registers its @activityInterface codecs via addActivityImplementation") {
      val taskQueue = "auto-reg-activity-queue"

      for {
        workflowId <- ZIO.randomWith(_.nextUUID)
        // Registers both PromiseWorkflow (interface) and PromiseActivityImpl (impl — walks to PromiseActivity)
        _ <- ZTestWorkflowEnvironment.newWorker(taskQueue) @@
               ZWorker.addWorkflow[PromiseWorkflowImpl].fromClass @@
               ZWorker.addActivityImplementation(new PromiseActivityImpl(x => x * 2, x => x + 1))
        _        <- ZTestWorkflowEnvironment.setup()
        workflow <- ZTestWorkflowEnvironment.newWorkflowStub[PromiseWorkflow](
                      ZWorkflowOptions
                        .withWorkflowId(workflowId.toString)
                        .withTaskQueue(taskQueue)
                        .withWorkflowRunTimeout(10.second)
                    )
        // foo(3) = 6, bar(4) = 5, sum = 11
        result <- ZWorkflowStub.execute(workflow.fooBar(3, 4))
      } yield assertTrue(result == 11)
    }.provideSomeLayer[Scope](emptyRegistryWorkflowEnv) @@ TestAspect.withLiveClock,
    test("auto-reg is idempotent: the same workflow added on three workers yields one codec entry") {
      // Build three workers on different task queues, register the same workflow on each, and inspect the
      // shared registry. CodecRegistry.register already dedupes identical codecs, so the registry should hold
      // exactly one SampleWorkflow's codec entries (plus its String return/parameter codecs), not three of each.
      for {
        _ <- ZTestWorkflowEnvironment.newWorker("dedup-queue-1") @@
               ZWorker.addWorkflow[SampleWorkflowImpl].fromClass
        _ <- ZTestWorkflowEnvironment.newWorker("dedup-queue-2") @@
               ZWorker.addWorkflow[SampleWorkflowImpl].fromClass
        _ <- ZTestWorkflowEnvironment.newWorker("dedup-queue-3") @@
               ZWorker.addWorkflow[SampleWorkflowImpl].fromClass
        registry <- ZIO.serviceWith[ZTestWorkflowEnvironment[Any]](
                      _.codecRegistry.getOrElse(throw new AssertionError("registry should be Some here"))
                    )
        typeNames = registry.registeredTypeNames.toSet
      } yield assertTrue(
        // SampleWorkflow.echo(str: String): String — String codec is registered exactly once.
        typeNames.count(_ == "java.lang.String") == 1
      )
    }.provideSomeLayer[Scope](emptyRegistryWorkflowEnv),
    test("opt-out: when withDataConverter(raw) is used, auto-reg at a call site is a silent no-op") {
      // Use Temporal's stock DataConverter (Jackson-based) rather than our zio-json one. `withDataConverter`
      // clears `codecRegistry` to `None`, so the macro-generated `None.foreach { r => r.register(...) }` body
      // never runs — and crucially, this must succeed (not throw) even though the call site references a
      // workflow interface and registry access would otherwise require `Some`. The worker creation returning
      // normally is what the test asserts.
      val rawConverter = DefaultDataConverter.STANDARD_INSTANCE
      val options      =
        ZWorkflowClientOptions.make @@ ZWorkflowClientOptions.withDataConverter(rawConverter)

      val customEnvLayer: ULayer[ZTestWorkflowEnvironment[Any]] =
        ZLayer.make[ZTestWorkflowEnvironment[Any]](
          options.orDie,
          ZWorkerFactoryOptions.make.orDie,
          ZTestEnvironmentOptions.make,
          ZTestWorkflowEnvironment.make[Any]
        )

      (for {
        env <- ZIO.service[ZTestWorkflowEnvironment[Any]]
        // Registry must be None when a custom DataConverter is in use.
        _ = assertTrue(env.codecRegistry.isEmpty)
        // Exercising an auto-reg call site with a None registry must succeed — the macro-generated
        // `None.foreach { r => r.register(...) }` body must compile and be inert at runtime.
        _ <- ZTestWorkflowEnvironment.newWorker("opt-out-queue") @@
               ZWorker.addWorkflow[SampleWorkflowImpl].fromClass @@
               ZWorker.addActivityImplementation(new PromiseActivityImpl(x => x, x => x))
        // The registry is still empty after the auto-reg sites ran.
      } yield assertTrue(
        env.codecRegistry.isEmpty
      )).provideSomeLayer[Scope](customEnvLayer)
    }
  )
}
