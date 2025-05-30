package zio.temporal.internal

import io.temporal.failure.TemporalException
import zio.ZIO
import zio.temporal.TemporalIO
import zio.temporal.internalApi
import java.util.concurrent.CompletableFuture
import scala.concurrent.TimeoutException

@internalApi
object TemporalInteraction {

  def from[A](thunk: => A): TemporalIO[A] =
    ZIO
      .attemptBlocking(thunk)
      .refineToOrDie[TemporalException]

  def fromFuture[A](future: => CompletableFuture[A]): TemporalIO[A] =
    ZIO
      .fromCompletableFuture(future)
      .refineToOrDie[TemporalException]

  def fromFutureTimeout[A](future: => CompletableFuture[A]): TemporalIO[Option[A]] =
    ZIO
      .fromCompletableFuture(future)
      .map(Option(_))
      .catchSome { case _: TimeoutException => ZIO.none }
      .refineToOrDie[TemporalException]
}
