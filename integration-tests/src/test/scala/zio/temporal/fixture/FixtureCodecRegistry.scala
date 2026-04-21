package zio.temporal.fixture

import zio.temporal.json.CodecRegistry

/** Shared [[CodecRegistry]] that pre-registers every `@workflowInterface` / `@activityInterface` defined in the
  * `zio.temporal.fixture` package.
  *
  * The integration-test suite uses `ZTestWorkflowEnvironment.makeDefault` for almost every spec, which wires a
  * `ZWorkflowClientOptions` whose default `CodecRegistry` is empty. Without this pre-population, every workflow would
  * fail at the first `execute()` with `"No ZTemporalCodec registered…"`, and the test would hang on the default
  * workflow retry policy. Registering all fixtures up-front in one place keeps the tests themselves tidy.
  *
  * Adding a new fixture: append `.addInterface[YourWorkflow]` below. The macro walks the interface at compile time and
  * fails the build if any referenced type lacks a `ZTemporalCodec` — so this list doubles as a smoke test for the
  * fixture types staying compatible with the codec gate.
  */
object FixtureCodecRegistry {

  val all: CodecRegistry =
    new CodecRegistry()
      .addInterface[ActivityWithDependencies]
      .addInterface[ComplexTypesActivity]
      .addInterface[ComplexWorkflow]
      .addInterface[ContinueAsNewNamedWorkflow]
      .addInterface[ContinueAsNewWorkflow]
      .addInterface[EitherWorkflow]
      .addInterface[FibonacciHeartbeatActivity]
      .addInterface[GreetingChild]
      .addInterface[GreetingNamedChild]
      .addInterface[GreetingNamedWorkflow]
      .addInterface[GreetingUntypedChild]
      .addInterface[GreetingUntypedWorkflow]
      .addInterface[GreetingWorkflow]
      .addInterface[JuiceChildWorkflow]
      .addInterface[JuiceWorkflow]
      .addInterface[MemoWorkflow]
      .addInterface[MultiActivitiesWorkflow]
      .addInterface[PaymentWorkflow]
      .addInterface[PromiseActivity]
      .addInterface[PromiseWorkflow]
      .addInterface[RetryWorkflow]
      .addInterface[SagaWorkflow]
      .addInterface[SampleNamedWorkflow]
      .addInterface[SampleWorkflow]
      .addInterface[SignalWithStartWorkflow]
      .addInterface[SignalWorkflow]
      .addInterface[SodaChildWorkflow]
      .addInterface[SodaWorkflow]
      .addInterface[TransferActivity]
      .addInterface[WorkflowBar]
      .addInterface[WorkflowBarUntyped]
      .addInterface[WorkflowFoo]
      .addInterface[WorkflowFooUntyped]
      .addInterface[ZioActivity]
      .addInterface[ZioLocalWorkflow]
      .addInterface[ZioUntypedActivity]
      .addInterface[ZioWorkflow]
      .addInterface[ZioWorkflowUntyped]
  // Intentionally excluded — these test `T | Null` erasure-warning behavior and use types the codec gate
  // can't handle, by design: ConcreteUnionWorkflow, ProblematicUnionWorkflow, IntOrNullWorkflow,
  // NewtypeWorkflow, StringOrNullWorkflow.
}
