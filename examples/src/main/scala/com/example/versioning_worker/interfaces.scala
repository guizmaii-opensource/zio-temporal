package com.example.versioning_worker

import zio.json.{DeriveJsonDecoder, DeriveJsonEncoder, JsonDecoder, JsonEncoder}
import zio.temporal._

@workflowInterface
trait SubscriptionWorkflow {

  @workflowMethod
  def proceedRecurringSubscription(subscriptionId: String): Unit
}

case class Subscription(
  id:        String,
  userEmail: String,
  amount:    BigDecimal)

object Subscription {
  implicit val encoder: JsonEncoder[Subscription] = DeriveJsonEncoder.gen[Subscription]
  implicit val decoder: JsonDecoder[Subscription] = DeriveJsonDecoder.gen[Subscription]
}

@activityInterface
trait SubscriptionActivities {

  def getSubscription(subscriptionId: String): Subscription

  /** @return
    *   paymentId
    */
  def proceedPayment(subscriptionId: String, amount: BigDecimal): String
}
