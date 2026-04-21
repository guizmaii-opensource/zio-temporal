package zio.temporal.workflow

import zio._
import zio.temporal.internal.ConfigurationCompanion
import io.temporal.api.enums.v1.QueryRejectCondition
import io.temporal.client.WorkflowClientOptions
import io.temporal.common.context.ContextPropagator
import io.temporal.common.converter._
import io.temporal.common.interceptors.WorkflowClientInterceptor
import zio.temporal.json.{CodecRegistry, ZioJsonDataConverter}
import scala.jdk.CollectionConverters._

/** Represents Temporal workflow client options
  *
  * @see
  *   [[WorkflowClientOptions]]
  */
final case class ZWorkflowClientOptions private[zio] (
  namespace:     Option[String],
  dataConverter: DataConverter,
  /** The `CodecRegistry` that the data converter is backed by, when the default zio-json data converter is in use.
    *
    * `Some(registry)` — the default path: `ZWorkflowClient.make` verifies this registry is non-empty and fails loudly
    * otherwise, catching the "forgot `.withCodecRegistry(...)` and `.addInterface[…]`" mistake at client-construction
    * time instead of at the first `execute()`.
    *
    * `None` — the user supplied a custom `DataConverter` via [[withDataConverter]]; we stop introspecting and trust
    * them.
    */
  codecRegistry: Option[CodecRegistry],
  interceptors:  List[WorkflowClientInterceptor],
  identity:      Option[String],
  @deprecated("use worker's buildId instead", since = "0.3.0")
  binaryChecksum:       Option[String],
  contextPropagators:   List[ContextPropagator],
  queryRejectCondition: Option[QueryRejectCondition],
  private val javaOptionsCustomization: WorkflowClientOptions.Builder => WorkflowClientOptions.Builder) {

  /** Set the namespace
    */
  def withNamespace(value: String): ZWorkflowClientOptions =
    copy(namespace = Some(value))

  /** Overrides a data converter implementation used serialize workflow and activity arguments and results.
    *
    * Clears [[codecRegistry]] — when the user supplies a custom converter, the fail-fast registry check at
    * `ZWorkflowClient.make` is disabled. Use [[withCodecRegistry]] or [[withCodecs]] if you want the check to stay in
    * effect.
    */
  def withDataConverter(value: DataConverter): ZWorkflowClientOptions =
    copy(dataConverter = value, codecRegistry = None)

  /** Interceptor used to intercept workflow client calls.
    */
  def withInterceptors(value: WorkflowClientInterceptor*): ZWorkflowClientOptions =
    copy(interceptors = value.toList)

  /** Override human readable identity of the worker. Identity is used to identify a worker and is recorded in the
    * workflow history events. For example when a worker gets an activity task the correspondent ActivityTaskStarted
    * event contains the worker identity as a field.
    */
  def withIdentity(value: String): ZWorkflowClientOptions =
    copy(identity = Some(value))

  /** Sets worker binary checksum, which gets propagated in all history events and can be used for auto-reset assuming
    * that every build has a new unique binary checksum. Can be null.
    *
    * @deprecated
    *   use [[zio.temporal.worker.ZWorkerOptions.withBuildId]] instead.
    */
  @deprecated("use worker's buildId instead", since = "0.3.0")
  def withBinaryChecksum(value: String): ZWorkflowClientOptions =
    copy(binaryChecksum = Some(value))

  /** @param value
    *   specifies the list of context propagators to use with the client.
    */
  def withContextPropagators(value: ContextPropagator*): ZWorkflowClientOptions =
    copy(contextPropagators = value.toList)

  /** Should a query be rejected by closed and failed workflows.
    *
    * <p>Default is [[QueryRejectCondition.QUERY_REJECT_CONDITION_UNSPECIFIED]] which means that closed and failed
    * workflows are still queryable.
    */
  def withQueryRejectCondition(value: QueryRejectCondition): ZWorkflowClientOptions =
    copy(queryRejectCondition = Some(value))

  /** Allows to specify options directly on the java SDK's [[WorkflowClientOptions]]. Use it in case an appropriate
    * `withXXX` method is missing
    *
    * @note
    *   the options specified via this method take precedence over those specified via other methods.
    */
  def transformJavaOptions(
    f: WorkflowClientOptions.Builder => WorkflowClientOptions.Builder
  ): ZWorkflowClientOptions =
    copy(javaOptionsCustomization = f)

  def toJava: WorkflowClientOptions = {
    val builder = WorkflowClientOptions.newBuilder()

    namespace.foreach(builder.setNamespace)
    builder.setDataConverter(dataConverter)
    builder.setInterceptors(interceptors: _*)
    identity.foreach(builder.setIdentity)
    binaryChecksum.foreach(builder.setBinaryChecksum)
    builder.setContextPropagators(contextPropagators.asJava)
    queryRejectCondition.foreach(builder.setQueryRejectCondition)

    javaOptionsCustomization(builder).build()
  }

  override def toString: String = {
    s"ZWorkflowClientOptions(" +
      s"namespace=$namespace" +
      s"dataConverter=$dataConverter" +
      s"interceptors=$interceptors" +
      s"identity=$identity" +
      s"binaryChecksum=$binaryChecksum" +
      s"contextPropagators=$contextPropagators" +
      s"queryRejectCondition=$queryRejectCondition" +
      s")"
  }
}

object ZWorkflowClientOptions extends ConfigurationCompanion[ZWorkflowClientOptions] {

  /** @see
    *   [[ZWorkflowClientOptions.withNamespace]]
    */
  def withNamespace(value: String): Configure =
    configure(_.withNamespace(value))

  /** @see
    *   [[ZWorkflowClientOptions.withDataConverter]]
    */
  def withDataConverter(value: => DataConverter): Configure =
    configure(_.withDataConverter(value))

  /** Convenience: configure the client to use a zio-json [[DataConverter]] backed by a [[CodecRegistry]] containing the
    * supplied codecs. Equivalent to:
    *
    * {{{
    *   ZWorkflowClientOptions.withDataConverter(
    *     ZioJsonDataConverter.make(CodecRegistry.of(codecs: _*))
    *   )
    * }}}
    */
  def withCodecs(codecs: zio.temporal.json.ZTemporalCodec[_]*): Configure = {
    val registry = zio.temporal.json.CodecRegistry.of(codecs: _*)
    configure(
      _.copy(dataConverter = zio.temporal.json.ZioJsonDataConverter.make(registry), codecRegistry = Some(registry))
    )
  }

  /** Convenience: configure the client to use the supplied [[CodecRegistry]] directly. Useful when the same registry is
    * shared between a client and a worker.
    */
  def withCodecRegistry(registry: zio.temporal.json.CodecRegistry): Configure =
    configure(
      _.copy(dataConverter = zio.temporal.json.ZioJsonDataConverter.make(registry), codecRegistry = Some(registry))
    )

  /** @see
    *   [[ZWorkflowClientOptions.withInterceptors]]
    */
  def withInterceptors(value: WorkflowClientInterceptor*): Configure =
    configure(_.withInterceptors(value: _*))

  /** @see
    *   [[ZWorkflowClientOptions.withIdentity]]
    */
  def withIdentity(value: String): Configure =
    configure(_.withIdentity(value))

  /** @see
    *   [[ZWorkflowClientOptions.withBinaryChecksum]]
    */
  @deprecated("use worker's buildId instead", since = "0.3.0")
  def withBinaryChecksum(value: String): Configure =
    configure(_.withBinaryChecksum(value))

  /** @see
    *   [[ZWorkflowClientOptions.withContextPropagators]]
    */
  def withContextPropagators(value: ContextPropagator*): Configure =
    configure(_.withContextPropagators(value: _*))

  /** @see
    *   [[ZWorkflowClientOptions.withQueryRejectCondition]]
    */
  def withQueryRejectCondition(value: QueryRejectCondition): Configure =
    configure(_.withQueryRejectCondition(value))

  /** @see
    *   [[ZWorkflowClientOptions.transformJavaOptions]]
    */
  def transformJavaOptions(
    f: WorkflowClientOptions.Builder => WorkflowClientOptions.Builder
  ): Configure = configure(_.transformJavaOptions(f))

  private val workflowClientConfig =
    Config.string("namespace").optional ++
      Config.string("identity").optional ++
      Config.string("binary_checksum").optional

  /** Reads config from the default path `zio.temporal.zworkflow_client`
    */
  val make: Layer[Config.Error, ZWorkflowClientOptions] =
    makeImpl(Nil)

  /** Allows to specify custom path for the config
    */
  def forPath(name: String, names: String*): Layer[Config.Error, ZWorkflowClientOptions] =
    makeImpl(List(name) ++ names)

  private def makeImpl(additionalPath: List[String]): Layer[Config.Error, ZWorkflowClientOptions] = {
    val config =
      additionalPath match {
        case Nil          => workflowClientConfig.nested("zio", "temporal", "zworkflow_client")
        case head :: tail => workflowClientConfig.nested(head, tail: _*)
      }
    ZLayer.fromZIO {
      ZIO.config(config).map { case (namespace, identityCfg, binaryChecksum) =>
        val registry = new CodecRegistry
        new ZWorkflowClientOptions(
          namespace = namespace,
          dataConverter = ZioJsonDataConverter.make(registry),
          codecRegistry = Some(registry),
          interceptors = Nil,
          identity = identityCfg,
          binaryChecksum = binaryChecksum,
          contextPropagators = Nil,
          queryRejectCondition = None,
          javaOptionsCustomization = identity
        )
      }
    }
  }
}
