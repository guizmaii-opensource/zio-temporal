package zio.temporal.activity

import zio._
import zio.temporal.json.CodecRegistry

/** Type-safe wrapper of activity implementation object. The wrapper can be constructed only if the wrapped object is a
  * correct activity implementation.
  *
  * Carries a `registerCodecs` thunk captured at construction time — when the element type `T` is still known to the
  * compiler — so that `ZWorker.addActivityImplementations(List[ZActivityImplementationObject[_]])` can auto-register
  * codecs for each activity interface without needing the element types back. The thunk is a no-op when the worker's
  * registry is `None` (user opted out via `withDataConverter(raw)`).
  *
  * @param value
  *   the activity implementation object
  * @param registerCodecs
  *   closure that, when invoked with the worker's registry option, registers every codec required by each
  *   `@activityInterface`-annotated supertype of `T`. Built at compile time by the macro in
  *   `ZActivityImplementationObject.apply[T]`.
  */
final class ZActivityImplementationObject[T <: AnyRef] private[zio] (
  val value:                          T,
  private[zio] val registerCodecs: Option[CodecRegistry] => Unit) {

  /** Secondary no-op constructor for binary compatibility with legacy callers that don't build via
    * `ZActivityImplementationObject[T](value)`. Auto-registration is skipped for impls constructed this way.
    */
  private[zio] def this(value: T) = this(value, _ => ())

  override def toString: String = {
    s"ZActivityImplementation(value=$value)"
  }

  override def hashCode(): Int = value.hashCode()

  override def equals(obj: Any): Boolean = {
    if (obj == null) false
    else
      obj match {
        case that: ZActivityImplementationObject[_] =>
          this.value == that.value
        case _ => false
      }
  }
}

object ZActivityImplementationObject {

  /** Internal builder used by the inline `apply` — needed because Scala 3 forbids inline methods from calling
    * `private[zio]` constructors directly. Users should use [[apply]].
    */
  @zio.temporal.internalApi
  def build[T <: AnyRef](value: T, registerCodecs: Option[CodecRegistry] => Unit): ZActivityImplementationObject[T] =
    new ZActivityImplementationObject[T](value, registerCodecs)

  /** Constructs the wrapper.
    *
    * The `inline` keyword + macro-expanded codec registration is what lets the wrapper carry a deferred thunk that
    * still knows `T`'s activity interface even after `T` is erased to `AnyRef` inside a
    * `List[ZActivityImplementationObject[_]]`.
    *
    * @tparam T
    *   activity type.
    *
    * @param value
    *   the correct activity implementation object
    * @return
    *   [[ZActivityImplementationObject]]
    */
  inline def apply[T <: AnyRef: ExtendsActivity](value: T): ZActivityImplementationObject[T] =
    build[T](
      value,
      registry => zio.temporal.json.CodecRegistry.autoRegisterActivityImpl[T](registry)
    )

  /** Constructs the wrapper from an ZIO environment */
  inline def service[T <: AnyRef: ExtendsActivity: Tag]: URIO[T, ZActivityImplementationObject[T]] =
    ZIO.serviceWith[T](ZActivityImplementationObject[T](_))

  /** Constructs the wrapper from a ZLayer */
  inline def layer[R, E, T <: AnyRef: ExtendsActivity: Tag](
    value: ZLayer[R, E, T]
  ): ZLayer[R, E, ZActivityImplementationObject[T]] =
    value >>> ZLayer.fromZIO(service[T])
}
