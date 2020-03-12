package com.evolutiongaming.akkaeffect.persistence

import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import cats.data.{NonEmptyList => Nel}
import cats.effect.concurrent.Ref
import cats.effect.{Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect._

object InstrumentEventSourced {

  def apply[F[_] : Sync, S, C, E, R](
    actions: Ref[F, List[Action[S, C, E, R]]],
    eventSourcedOf: EventSourcedOf[F, S, C, E, R]
  ): EventSourcedOf[F, S, C, E, R] = {

    def record(action: Action[S, C, E, R]) = actions.update { action :: _ }

    def resource[A](allocate: Action[S, C, E, R], release: Action[S, C, E, R]) = {
      Resource.make {
        record(allocate)
      } { _ =>
        record(release)
      }
    }

    ctx: ActorCtx[F, C, R] => {
      for {
        eventSourced <- eventSourcedOf(ctx)
        _            <- record(Action.Created(
          eventSourced.id,
          eventSourced.recovery,
          eventSourced.pluginIds))
      } yield {
        new EventSourced[F, S, C, E, R] {

          def id = eventSourced.id

          def start = {
            for {
              started <- eventSourced.start
              _       <- resource(Action.Started, Action.Released)
            } yield for {
              started <- started
            } yield {
              snapshotOffer: Option[SnapshotOffer[S]] => {

                val snapshotOffer1 = snapshotOffer.map { snapshotOffer =>
                  val metadata = snapshotOffer.metadata.copy(timestamp = 0)
                  snapshotOffer.copy(metadata = metadata)
                }

                for {
                  recovering <- started.recoveryStarted(snapshotOffer)
                  _          <- resource(Action.RecoveryAllocated(snapshotOffer1), Action.RecoveryReleased)
                } yield for {
                  recovering <- recovering
                } yield {

                  new Recovering[F, S, C, E, R] {

                    def initial = for {
                      state <- recovering.initial
                      _     <- record(Action.Initial(state))
                    } yield state

                    def replay = {
                      for {
                        _      <- resource(Action.ReplayAllocated, Action.ReplayReleased)
                        replay <- recovering.replay
                      } yield {
                        new Replay[F, S, E] {
                          def apply(state: S, event: E, seqNr: SeqNr) = {
                            for {
                              after <- replay(state, event, seqNr)
                              _     <- record(Action.Replayed(state, event, seqNr, after))
                            } yield after
                          }
                        }
                      }
                    }

                    def completed(
                      state: S,
                      seqNr: SeqNr,
                      journaller: Journaller[F, E],
                      snapshotter: Snapshotter[F, S]
                    ) = {

                      val journaller1 = new Journaller[F, E] {

                        def append = (events: Nel[Nel[E]]) => {
                          for {
                            _     <- record(Action.AppendEvents(events))
                            seqNr <- journaller.append(events)
                            _     <- record(Action.AppendEventsOuter)
                          } yield {
                            for {
                              seqNr <- seqNr
                              _     <- record(Action.AppendEventsInner(seqNr))
                            } yield seqNr
                          }
                        }

                        def deleteTo(seqNr: SeqNr) = {
                          for {
                            _ <- record(Action.DeleteEventsTo(seqNr))
                            a <- journaller.deleteTo(seqNr)
                            _ <- record(Action.DeleteEventsToOuter)
                          } yield {
                            for {
                              a <- a
                              _ <- record(Action.DeleteEventsToInner)
                            } yield a
                          }
                        }
                      }

                      val snapshotter1 = new Snapshotter[F, S] {

                        def save(seqNr: SeqNr, snapshot: S) = {
                          for {
                            _ <- record(Action.SaveSnapshot(seqNr, snapshot))
                            a <- snapshotter.save(seqNr, snapshot)
                            _ <- record(Action.SaveSnapshotOuter)
                          } yield for {
                            a <- a
                            _ <- record(Action.SaveSnapshotInner)
                          } yield a
                        }

                        def delete(seqNr: SeqNr) = {
                          for {
                            _ <- record(Action.DeleteSnapshot(seqNr))
                            a <- snapshotter.delete(seqNr)
                            _ <- record(Action.DeleteSnapshotOuter)
                          } yield {
                            for {
                              a <- a
                              _ <- record(Action.DeleteSnapshotInner)
                            } yield a
                          }
                        }

                        def delete(criteria: SnapshotSelectionCriteria) = {
                          for {
                            _ <- record(Action.DeleteSnapshots(criteria))
                            a <- snapshotter.delete(criteria)
                            _ <- record(Action.DeleteSnapshotsOuter)
                          } yield {
                            for {
                              a <- a
                              _ <- record(Action.DeleteSnapshotsInner)
                            } yield a
                          }
                        }
                      }

                      for {
                        receive <- recovering.completed(state, seqNr, journaller1, snapshotter1)
                        _       <- resource(Action.ReceiveAllocated(state, seqNr), Action.ReceiveReleased)
                      } yield for {
                        receive <- receive
                      } yield {
                        new Receive[F, C, R] {
                          def apply(msg: C, reply: Reply[F, R]) = {

                            val reply1 = new Reply[F, R] {
                              def apply(msg: R) = {
                                for {
                                  _ <- record(Action.Replied(msg))
                                  a <- reply(msg)
                                } yield a
                              }
                            }
                            for {
                              stop <- receive(msg, reply1)
                              _    <- record(Action.Received(msg, stop))
                            } yield stop
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }

        }
      }
    }
  }


  sealed trait Action[+S, +C, +E, +R]

  object Action {

    final case class Created(
      persistenceId: String,
      recovery: Recovery,
      pluginIds: PluginIds
    ) extends Action[Nothing, Nothing, Nothing, Nothing]


    final case object Started extends Action[Nothing, Nothing, Nothing, Nothing]

    final case object Released extends Action[Nothing, Nothing, Nothing, Nothing]


    final case class RecoveryAllocated[S](
      snapshotOffer: Option[SnapshotOffer[S]],
    ) extends Action[S, Nothing, Nothing, Nothing]

    final case object RecoveryReleased extends Action[Nothing, Nothing, Nothing, Nothing]


    final case class Initial[S, E](state: S) extends Action[S, Nothing, Nothing, Nothing]


    final case object ReplayAllocated extends Action[Nothing, Nothing, Nothing, Nothing]

    final case object ReplayReleased extends Action[Nothing, Nothing, Nothing, Nothing]


    final case class Replayed[S, E](before: S, event: E, seqNr: SeqNr, after: S) extends Action[S, Nothing, E, Nothing]


    final case class AppendEvents[E](events: Nel[Nel[E]]) extends Action[Nothing, Nothing, E, Nothing]

    final case object AppendEventsOuter extends Action[Nothing, Nothing, Nothing, Nothing]

    final case class AppendEventsInner(seqNr: SeqNr) extends Action[Nothing, Nothing, Nothing, Nothing]


    final case class DeleteEventsTo(seqNr: SeqNr) extends Action[Nothing, Nothing, Nothing, Nothing]

    final case object DeleteEventsToOuter extends Action[Nothing, Nothing, Nothing, Nothing]

    final case object DeleteEventsToInner extends Action[Nothing, Nothing, Nothing, Nothing]


    final case class SaveSnapshot[S](seqNr: SeqNr, snapshot: S) extends Action[S, Nothing, Nothing, Nothing]

    final case object SaveSnapshotOuter extends Action[Nothing, Nothing, Nothing, Nothing]

    final case object SaveSnapshotInner extends Action[Nothing, Nothing, Nothing, Nothing]


    final case class DeleteSnapshot(seqNr: SeqNr) extends Action[Nothing, Nothing, Nothing, Nothing]

    final case object DeleteSnapshotOuter extends Action[Nothing, Nothing, Nothing, Nothing]

    final case object DeleteSnapshotInner extends Action[Nothing, Nothing, Nothing, Nothing]


    final case class DeleteSnapshots(criteria: SnapshotSelectionCriteria) extends Action[Nothing, Nothing, Nothing, Nothing]

    final case object DeleteSnapshotsOuter extends Action[Nothing, Nothing, Nothing, Nothing]

    final case object DeleteSnapshotsInner extends Action[Nothing, Nothing, Nothing, Nothing]


    final case class ReceiveAllocated[S](state: S, seqNr: SeqNr) extends Action[S, Nothing, Nothing, Nothing]

    final case object ReceiveReleased extends Action[Nothing, Nothing, Nothing, Nothing]

    final case class Received[C](cmd: C, stop: Receive.Stop) extends Action[Nothing, C, Nothing, Nothing]

    final case class Replied[R](reply: R) extends Action[Nothing, Nothing, Nothing, R]
  }
}
