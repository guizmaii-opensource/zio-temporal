package com.example.versioning_worker

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}
import zio.temporal.json.ZTemporalCodec
import zio.temporal._

@workflowInterface
trait SubscriptionWorkflow {

  @workflowMethod
  def proceedRecurringSubscription(subscriptionId: String): Unit
}

final case class Subscription(
  id:        String,
  userEmail: String,
  amount:    BigDecimal) derives ZTemporalCodec

@activityInterface
trait SubscriptionActivities {

  def getSubscription(subscriptionId: String): Subscription

  /** @return
    *   paymentId
    */
  def proceedPayment(subscriptionId: String, amount: BigDecimal): String
}
