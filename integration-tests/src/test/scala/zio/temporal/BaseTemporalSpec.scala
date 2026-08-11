package zio.temporal

import zio._
import zio.temporal.fixture.FixtureCodecRegistry
import zio.temporal.testkit.{ZTestActivityEnvironment, ZTestEnvironmentOptions, ZTestWorkflowEnvironment}
import zio.temporal.worker.ZWorkerFactoryOptions
import zio.temporal.workflow.ZWorkflowClientOptions
import zio.test._

abstract class BaseTemporalSpec extends ZIOSpecDefault {

  /** [[ZWorkflowClientOptions]] pre-wired with [[FixtureCodecRegistry.all]]. Every `@workflowInterface` /
    * `@activityInterface` under `zio.temporal.fixture` is pre-registered, so every spec can serialize any fixture type
    * out of the box.
    *
    * Also exposed (protected) so specs that can't use `provideTestWorkflowEnv` — because they build a custom layer
    * stack around `ZTestWorkflowEnvironment.make[_]` / `ZTestActivityEnvironment.make[_]` — can thread the registry in
    * directly. Using `ZTestWorkflowEnvironment.makeDefault[_]` or `ZTestActivityEnvironment.makeDefault[_]` in a spec
    * defaults the registry to empty, which turns every `execute()` into an infinite retry loop → CI hang.
    */
  protected val clientOptionsWithFixtures: ULayer[ZWorkflowClientOptions] =
    (ZWorkflowClientOptions.make @@
      ZWorkflowClientOptions.withCodecRegistry(FixtureCodecRegistry.all)).orDie

  /** Full [[ZTestWorkflowEnvironment]] layer wired with [[FixtureCodecRegistry.all]]. Used by
    * [[ProvideWorkflowEnvironmentOps.provideTestWorkflowEnv]] and exposed `protected` so specs that need to plug it
    * into a larger layer stack (e.g. combining with `ReporterService`) can do so without reinventing the wiring.
    */
  protected val workflowEnvLayer: ULayer[ZTestWorkflowEnvironment[Any]] =
    ZLayer.make[ZTestWorkflowEnvironment[Any]](
      clientOptionsWithFixtures,
      ZWorkerFactoryOptions.make.orDie,
      ZTestEnvironmentOptions.make,
      ZTestWorkflowEnvironment.make[Any]
    )

  /** Full [[ZTestActivityEnvironment]] layer wired with [[FixtureCodecRegistry.all]]. Replaces
    * `ZTestActivityEnvironment.makeDefault[Any]` for the same reason as [[workflowEnvLayer]]: activity payload
    * serialization still flows through the `DataConverter`, so an empty registry fails at the first call.
    */
  protected val activityEnvLayer: ULayer[ZTestActivityEnvironment[Any]] =
    ZLayer.make[ZTestActivityEnvironment[Any]](
      clientOptionsWithFixtures,
      ZWorkerFactoryOptions.make.orDie,
      ZTestEnvironmentOptions.make,
      ZTestActivityEnvironment.make[Any]
    )

  protected implicit class ProvideWorkflowEnvironmentOps[E, A](
    thunk: Spec[ZTestWorkflowEnvironment[Any] with Scope, E]) {
    def provideTestWorkflowEnv: Spec[Scope, E] =
      thunk.provideSome[Scope](workflowEnvLayer) @@ TestAspect.withLiveClock
  }

  protected implicit class ProvideActivityEnvironmentOps[E, A](
    thunk: Spec[ZTestActivityEnvironment[Any] with Scope, E]) {
    def provideTestActivityEnv: Spec[Scope, E] =
      thunk.provideSome[Scope](activityEnvLayer) @@ TestAspect.withLiveClock
  }
}
