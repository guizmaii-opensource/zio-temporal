package zio.temporal

import zio._
import zio.temporal.fixture.FixtureCodecRegistry
import zio.temporal.testkit.{ZTestActivityEnvironment, ZTestEnvironmentOptions, ZTestWorkflowEnvironment}
import zio.temporal.worker.ZWorkerFactoryOptions
import zio.temporal.workflow.ZWorkflowClientOptions
import zio.test._

abstract class BaseTemporalSpec extends ZIOSpecDefault {

  /** Test environment wired with [[FixtureCodecRegistry.all]]. Every `@workflowInterface` / `@activityInterface` under
    * `zio.temporal.fixture` is pre-registered, so every spec can serialize any fixture type out of the box.
    *
    * Replaces `ZTestWorkflowEnvironment.makeDefault`, whose `ZWorkflowClientOptions.make` defaulted to an empty
    * registry and caused workflows to fail at the first `execute()` → infinite retry loop → CI hang.
    */
  private val workflowEnvLayer: ULayer[ZTestWorkflowEnvironment[Any]] = {
    val clientOptions: ULayer[ZWorkflowClientOptions] =
      (ZWorkflowClientOptions.make @@
        ZWorkflowClientOptions.withCodecRegistry(FixtureCodecRegistry.all)).orDie
    ZLayer.make[ZTestWorkflowEnvironment[Any]](
      clientOptions,
      ZWorkerFactoryOptions.make.orDie,
      ZTestEnvironmentOptions.make,
      ZTestWorkflowEnvironment.make[Any]
    )
  }

  protected implicit class ProvideWorkflowEnvironmentOps[E, A](
    thunk: Spec[ZTestWorkflowEnvironment[Any] with Scope, E]) {
    def provideTestWorkflowEnv: Spec[Scope, E] =
      thunk.provideSome[Scope](workflowEnvLayer) @@ TestAspect.withLiveClock
  }

  protected implicit class ProvideActivityEnvironmentOps[E, A](
    thunk: Spec[ZTestActivityEnvironment[Any] with Scope, E]) {
    def provideTestActivityEnv: Spec[Scope, E] =
      thunk.provideSome[Scope](ZTestActivityEnvironment.makeDefault[Any]) @@ TestAspect.withLiveClock
  }
}
