package com.example.bookingsaga

import zio._
import zio.temporal._
import zio.temporal.workflow._
import zio.temporal.activity._

class TripBookingWorkflowImpl extends TripBookingWorkflow {
  private val activities: ZActivityStub.Of[TripBookingActivities] = ZWorkflow
    .newActivityStub[TripBookingActivities](
      ZActivityOptions
        .withStartToCloseTimeout(1.hour)
        .withRetryOptions(
          ZRetryOptions.default.withMaximumAttempts(1)
        )
    )

  override def bookTrip(name: String): Unit = {
    val bookingSaga = for {
      // Attempt the action, then register its compensation
      carReservationID <- ZSaga.attempt(
                            ZActivityStub.execute(
                              activities.reserveCar(name)
                            )
                          )
      _ <- ZSaga.compensation(
             ZActivityStub.execute(
               activities.cancelCar(carReservationID, name)
             )
           )
      hotelReservationID <- ZSaga.attempt(
                              ZActivityStub.execute(
                                activities.bookHotel(name)
                              )
                            )
      _ <- ZSaga.compensation(
             ZActivityStub.execute(
               activities.cancelHotel(hotelReservationID, name)
             )
           )
      flightReservationID <- ZSaga.attempt(
                               ZActivityStub.execute(
                                 activities.bookFlight(name)
                               )
                             )
      _ <- ZSaga.compensation(
             ZActivityStub.execute(
               activities.cancelFlight(flightReservationID, name)
             )
           )
    } yield ()

    bookingSaga.runOrThrow(
      options = ZSaga.Options(parallelCompensation = true)
    )
  }
}
