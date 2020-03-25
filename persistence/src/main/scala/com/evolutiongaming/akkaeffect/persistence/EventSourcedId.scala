package com.evolutiongaming.akkaeffect.persistence

import cats.implicits._
import cats.{Order, Show}

/**
  * @see [[akka.persistence.PersistentActor.persistenceId]]
  */
final case class EventSourcedId(value: String)

object EventSourcedId {

  implicit val orderEventSourcedId: Order[EventSourcedId] = Order.by { a: EventSourcedId => a.value }

  implicit val showEventSourcedId: Show[EventSourcedId] = Show.fromToString
}