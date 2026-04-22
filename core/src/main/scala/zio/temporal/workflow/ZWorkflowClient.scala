package zio.temporal.workflow

import io.temporal.client.{ActivityCompletionClient, BuildIdOperation, WorkflowClient}
import zio._
import zio.stream._
import zio.temporal.internal.{ClassTagUtils, TemporalInteraction, TemporalWorkflowFacade}
import zio.temporal.json.CodecRegistry
import zio.temporal.{TemporalIO, ZHistoryEvent, ZWorkflowExecutionHistory, ZWorkflowExecutionMetadata}
import scala.jdk.OptionConverters._
import scala.reflect.ClassTag

/** Represents Temporal workflow client
  *
  * The `codecRegistry` carried here is the ''same'' `CodecRegistry` instance that backs this client's `DataConverter`
  * (when the default zio-json data converter is in use). Auto-registration call sites (`newWorkflowStub[A]`,
  * `ZWorker.addWorkflow[I]`, …) mutate it at invocation time so users no longer need to chain `.addInterface[...]` by
  * hand. `None` indicates the user supplied a custom `DataConverter` via `withDataConverter(raw)`; in that case
  * auto-registration is a silent no-op.
  *
  * @see
  *   [[WorkflowClient]]
  */
final class ZWorkflowClient private[zio] (
  val toJava: WorkflowClient,
  private[zio] val codecRegistry: Option[CodecRegistry]) {

  /** Secondary constructor retained for call sites that don't have a registry reference. */
  private[zio] def this(toJava: WorkflowClient) = this(toJava, None)

  /** Creates new ActivityCompletionClient
    * @see
    *   [[ActivityCompletionClient]]
    */
  def newActivityCompletionClient: UIO[ActivityCompletionClient] =
    ZIO.succeedBlocking(toJava.newActivityCompletionClient())

  /** Creates new typed workflow stub builder.
    *
    * Auto-registration: the codecs for every parameter and return type of `A`'s `@workflowMethod` / `@signalMethod` /
    * `@queryMethod` methods are registered into this client's `CodecRegistry` at compile time. Opt-out
    * (`withDataConverter(raw)` → `codecRegistry = None`) is a silent no-op.
    *
    * @tparam A
    *   workflow interface
    * @return
    *   builder instance
    */
  @deprecated("Use newWorkflowStub accepting ZWorkerOptions", since = "0.6.0")
  inline def newWorkflowStub[A: ClassTag: IsWorkflow]: ZWorkflowStubBuilderTaskQueueDsl.Of[A] = {
    zio.temporal.json.CodecRegistry.autoRegisterInterface[A](codecRegistry)
    ZWorkflowClient.buildTaskQueueDsl[A](this)
  }

  /** Creates workflow client stub that can be used to start a single workflow execution. The first call must be to a
    * method annotated with @[[zio.temporal.workflowMethod]]. After workflow is started it can be also used to send
    * signals or queries to it. IMPORTANT! Stub is per workflow instance. So new stub should be created for each new
    * one.
    *
    * Auto-registration: same contract as above. Applies to all three `newWorkflowStub[A]` overloads.
    *
    * @tparam A
    *   interface that given workflow implements
    * @param options
    *   options that will be used to configure and start a new workflow.
    * @return
    *   Stub that implements workflowInterface and can be used to start workflow and signal or query it after the start.
    */
  inline def newWorkflowStub[A: ClassTag: IsWorkflow](options: ZWorkflowOptions): UIO[ZWorkflowStub.Of[A]] = {
    zio.temporal.json.CodecRegistry.autoRegisterInterface[A](codecRegistry)
    TemporalWorkflowFacade.createWorkflowStubTyped[A](toJava).apply(options.toJava)
  }

  /** Creates workflow client stub for a known execution. Use it to send signals or queries to a running workflow. Do
    * not call methods annotated with @[[zio.temporal.workflowMethod]].
    *
    * @tparam A
    *   interface that given workflow implements.
    * @param workflowId
    *   Workflow id.
    * @param runId
    *   Run id of the workflow execution.
    * @return
    *   Stub that implements workflowInterface and can be used to signal or query it.
    */
  inline def newWorkflowStub[A: ClassTag: IsWorkflow](
    workflowId: String,
    runId:      Option[String] = None
  ): UIO[ZWorkflowStub.Of[A]] = {
    zio.temporal.json.CodecRegistry.autoRegisterInterface[A](codecRegistry)
    ZWorkflowClient.buildWorkflowStubFromIds[A](this, workflowId, runId)
  }

  /** Creates new untyped type workflow stub builder
    *
    * @param workflowType
    *   name of the workflow type
    * @return
    *   builder instance
    */
  @deprecated("Use newUntypedWorkflowStub accepting ZWorkerOptions", since = "0.6.0")
  def newUntypedWorkflowStub(workflowType: String): ZWorkflowStubBuilderTaskQueueDsl.Untyped =
    new ZWorkflowStubBuilderTaskQueueDsl.Untyped(TemporalWorkflowFacade.createWorkflowStubUntyped(workflowType, toJava))

  /** Creates workflow untyped client stub that can be used to start a single workflow execution. After workflow is
    * started it can be also used to send signals or queries to it. IMPORTANT! Stub is per workflow instance. So new
    * stub should be created for each new one.
    *
    * @param workflowType
    *   name of the workflow type
    * @param options
    *   options used to start a workflow through returned stub
    * @return
    *   Stub that can be used to start workflow and later to signal or query it.
    */
  def newUntypedWorkflowStub(workflowType: String, options: ZWorkflowOptions): UIO[ZWorkflowStub.Untyped] =
    TemporalWorkflowFacade.createWorkflowStubUntyped(workflowType, toJava)(options.toJava)

  /** Creates workflow untyped client stub for a known execution. Use it to send signals or queries to a running
    * workflow. Do not call methods annotated with @[[zio.temporal.workflowMethod]].
    *
    * @param workflowId
    *   workflow id and optional run id for execution
    * @param runId
    *   runId of the workflow execution. If not provided the last workflow with the given workflowId is assumed.
    * @return
    *   Stub that can be used to start workflow and later to signal or query it.
    */
  def newUntypedWorkflowStub(
    workflowId: String,
    runId:      Option[String]
  ): UIO[ZWorkflowStub.Untyped] =
    ZIO.succeed {
      new ZWorkflowStub.UntypedImpl(
        toJava.newUntypedWorkflowStub(workflowId, runId.toJava, Option.empty[String].toJava)
      )
    }

  /** A wrapper around {WorkflowServiceStub#listWorkflowExecutions(ListWorkflowExecutionsRequest)}
    *
    * @param query
    *   Temporal Visibility Query, for syntax see <a href="https://docs.temporal.io/visibility#list-filter">Visibility
    *   docs</a>
    * @return
    *   sequential stream that performs remote pagination under the hood
    */
  def streamExecutions(query: Option[String] = None): Stream[Throwable, ZWorkflowExecutionMetadata] = {
    ZStream
      .blocking(
        ZStream.fromJavaStreamZIO(
          ZIO.attempt(
            toJava.listExecutions(query.orNull)
          )
        )
      )
      .map(new ZWorkflowExecutionMetadata(_))
  }

  /** Streams history events for a workflow execution for the provided `workflowId`.
    *
    * @param workflowId
    *   Workflow Id of the workflow to export the history for
    * @param runId
    *   Fixed Run Id of the workflow to export the history for. If not provided, the latest run will be used. Optional
    * @return
    *   stream of history events of the specified run of the workflow execution.
    * @see
    *   [[fetchHistory]] for a user-friendly eager version of this method
    */
  def streamHistory(workflowId: String, runId: Option[String] = None): Stream[Throwable, ZHistoryEvent] =
    ZStream
      .blocking(
        ZStream.fromJavaStreamZIO(
          ZIO.attempt(
            toJava.streamHistory(workflowId, runId.orNull)
          )
        )
      )
      .map(new ZHistoryEvent(_))

  /** Downloads workflow execution history for the provided `workflowId`.
    *
    * @param workflowId
    *   Workflow Id of the workflow to export the history for
    * @param runId
    *   Fixed Run Id of the workflow to export the history for. If not provided, the latest run will be used. Optional
    * @return
    *   execution history of the workflow with the specified Workflow Id.
    * @see
    *   [[streamHistory]] for a lazy memory-efficient version of this method
    */
  def fetchHistory(workflowId: String, runId: Option[String] = None): Task[ZWorkflowExecutionHistory] =
    ZIO
      .attemptBlocking(toJava.fetchHistory(workflowId, runId.orNull))
      .map(new ZWorkflowExecutionHistory(_))

  /** Allows you to update the worker-build-id based version sets for a particular task queue. This is used in
    * conjunction with workers who specify their build id and thus opt into the feature.
    *
    * @param taskQueue
    *   The task queue to update the version set(s) of.
    * @param operation
    *   The operation to perform. See [[BuildIdOperation]] for more.
    * @throws io.temporal.client.WorkflowServiceException
    *   for any failures including networking and service availability issues.
    * @note
    *   experimental in Java SDK
    */
  def updateWorkerBuildIdCompatibility(taskQueue: String, operation: BuildIdOperation): TemporalIO[Unit] =
    TemporalInteraction.from {
      toJava.updateWorkerBuildIdCompatability(taskQueue, operation)
    }

  /** Returns the worker-build-id based version sets for a particular task queue.
    *
    * @param taskQueue
    *   The task queue to fetch the version set(s) of.
    * @return
    *   The version set(s) for the task queue.
    * @throws io.temporal.client.WorkflowServiceException
    *   for any failures including networking and service availability issues.
    * @note
    *   experimental in Java SDK
    */
  def getWorkerBuildIdCompatibility(taskQueue: String): TemporalIO[ZWorkerBuildIdVersionSets] =
    TemporalInteraction.from {
      new ZWorkerBuildIdVersionSets(
        toJava.getWorkerBuildIdCompatability(taskQueue)
      )
    }
}

object ZWorkflowClient {

  /** Internal helper used by `inline def newWorkflowStub[A]` (no args) — needed because Scala 3 inline methods cannot
    * directly invoke `private[zio]` constructors. Package-private so only the inline site can call it.
    */
  @zio.temporal.internalApi
  def buildTaskQueueDsl[A: ClassTag](client: ZWorkflowClient): ZWorkflowStubBuilderTaskQueueDsl.Of[A] =
    new ZWorkflowStubBuilderTaskQueueDsl.Of[A](TemporalWorkflowFacade.createWorkflowStubTyped[A](client.toJava))

  /** Internal helper used by `inline def newWorkflowStub[A](workflowId, runId)`. */
  @zio.temporal.internalApi
  def buildWorkflowStubFromIds[A: ClassTag](
    client:     ZWorkflowClient,
    workflowId: String,
    runId:      Option[String]
  ): UIO[ZWorkflowStub.Of[A]] =
    ZIO.succeed {
      ZWorkflowStub.Of[A](
        new ZWorkflowStubImpl(
          client.toJava.newUntypedWorkflowStub(workflowId, runId.toJava, Option.empty[String].toJava),
          ClassTagUtils.classOf[A]
        )
      )
    }

  /** Create [[ZWorkflowClient]] instance.
    *
    * The `codecRegistry` carried by [[ZWorkflowClientOptions]] is threaded into the resulting client so the
    * auto-registration call sites (`ZWorker.addWorkflow[I]`, `newWorkflowStub[A]`, …) can populate it at their natural
    * usage points, eliminating the need for manual `.addInterface[...]` calls.
    *
    * The registry is allowed to start empty: codecs are added incrementally by `addWorkflow` / `newWorkflowStub` /
    * `addActivityImplementation`. If the user forgets to register any workflow or activity type at all, the first
    * payload encode produces a clear "No ZTemporalCodec registered for runtime class …" error from
    * `ZioJsonPayloadConverter`.
    *
    * @see
    *   [[WorkflowClient]]
    */
  val make: URLayer[ZWorkflowServiceStubs with ZWorkflowClientOptions, ZWorkflowClient] =
    ZLayer.fromZIO {
      ZIO.environmentWithZIO[ZWorkflowServiceStubs with ZWorkflowClientOptions] { environment =>
        val options = environment.get[ZWorkflowClientOptions]
        ZIO.succeedBlocking {
          new ZWorkflowClient(
            WorkflowClient.newInstance(
              environment.get[ZWorkflowServiceStubs].toJava,
              options.toJava
            ),
            options.codecRegistry
          )
        }
      }
    }
}
