package com.evolutiongaming.akkaeffect.persistence

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify, ReceiveTimeout}
import akka.persistence.{Recovery, SnapshotMetadata}
import akka.testkit.TestActors
import cats.data.{NonEmptyList => Nel}
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, IO, Resource, Sync, Timer}
import cats.implicits._
import com.evolutiongaming.akkaeffect.AkkaEffectHelper._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect.persistence.InstrumentEventSourced.Action
import com.evolutiongaming.akkaeffect.testkit.Probe
import com.evolutiongaming.akkaeffect.{ActorSuite, _}
import com.evolutiongaming.catshelper.CatsHelper._
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.reflect.ClassTag

class PersistentActorOfTest extends AsyncFunSuite with ActorSuite with Matchers {

  private implicit val toTry = ToTryFromToFuture.syncOrError[IO]

  test("all") {
    `persistentActorOf`[IO](actorSystem).run()
  }

  test("recover with empty state") {
    `recover with empty state`(actorSystem).run()
  }

  test("recover from snapshot") {
    `recover from snapshot`(actorSystem).run()
  }

  test("recover from snapshot with deleted events") {
    `recover from snapshot with deleted events`(actorSystem).run()
  }

  test("recover from events") {
    `recover from events`(actorSystem).run()
  }

  test("recover from events with deleted snapshot") {
    `recover from events with deleted snapshot`(actorSystem).run()
  }

  test("recover from snapshot and events") {
    `recover from snapshot and events`(actorSystem).run()
  }

  test("start stops") {
    `start stops`(actorSystem).run()
  }

  test("recoveryStarted stops") {
    `recoveryStarted stops`(actorSystem).run()
  }

  test("recoveryCompleted stops") {
    `recoveryCompleted stops`(actorSystem).run()
  }

  test("append many") {
    `append many`(actorSystem).run()
  }

  test("setReceiveTimeout") {
    setReceiveTimeout[IO](actorSystem).run()
  }

  private def `persistentActorOf`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {

    type State = Int
    type Event = String

    sealed trait Cmd

    object Cmd {

      def timeout: Cmd = Timeout

      final case object Inc extends Cmd
      final case object Stop extends Cmd
      final case object Timeout extends Cmd
      final case class WithCtx[A](f: ActorCtx[F] => F[A]) extends Cmd
    }

    def eventSourcedOf(receiveTimeout: F[Unit]): EventSourcedOf[F, State, Any, Event, Any] = {
      actorCtx => {

        val eventSourced = new EventSourced[F, State, Any, Event, Any] {

          def eventSourcedId = EventSourcedId("id")

          def pluginIds = PluginIds.empty

          def recovery = Recovery()

          def start = {
            val started = RecoveryStarted[F, State, Any, Event, Any] { (_, _) =>

                val recovering: Recovering[F, State, Any, Event, Any] = new Recovering[F, State, Any, Event, Any] {

                  def initial = 0.pure[F]

                  val replay = Replay1.empty[F, State, Event].pure[Resource[F, *]]

                  def completed(
                    seqNr: SeqNr,
                    state: State,
                    journaller: Journaller[F, Event],
                    snapshotter: Snapshotter[F, State]
                  ) = {

                    for {
                      stateRef <- Ref[F].of(state).toResource
                    } yield {
                      val receive = Receive[F, Cmd, Any] { (msg, reply) =>
                        msg match {
                          case a: Cmd.WithCtx[_] =>
                            for {
                              a <- a.f(actorCtx)
                              _ <- reply(a)
                            } yield false

                          case Cmd.Timeout =>
                            for {
                              _ <- actorCtx.setReceiveTimeout(Duration.Inf)
                              _ <- receiveTimeout
                            } yield false

                          case Cmd.Inc =>
                            for {
                              seqNr  <- journaller.append(Nel.of(Nel.of("a"))).flatten
                              _      <- stateRef.update { _ + 1 }
                              state  <- stateRef.get
                              result <- snapshotter.save(seqNr, state)
                              seqNr  <- journaller.append(Nel.of(Nel.of("b"), Nel.of("c", "d")))
                              seqNr  <- seqNr
                              _      <- result
                              _      <- stateRef.update { _ + 1 }
                              _      <- reply(seqNr)
                            } yield false

                          case Cmd.Stop =>
                            for {
                              _ <- reply("stopping")
                            } yield true
                        }
                      }

                      receive
                        .convertMsg[Any] {
                          case ReceiveTimeout => Cmd.timeout.pure[F]
                          case a              => a.cast[F, Cmd]
                        }
                    }
                  }
                }
                recovering.pure[Resource[F, *]]
            }

            started.pure[Resource[F, *]]
          }
        }
        eventSourced.pure[F]
      }
    }

    def persistentActorOf(
      actorRef: ActorEffect[F, Any, Any],
      probe: Probe[F],
      receiveTimeout: F[Unit],
    ): F[Unit] = {

      val timeout = 10.seconds

      def withCtx[A: ClassTag](f: ActorCtx[F] => F[A]): F[A] = {
        for {
          a <- actorRef.ask(Cmd.WithCtx(f), timeout)
          a <- a
          a <- a.cast[F, A]
        } yield a
      }

      for {
        terminated0 <- probe.watch(actorRef.toUnsafe)
        dispatcher  <- withCtx { _.executor.pure[F] }
        _           <- Sync[F].delay { dispatcher.toString shouldEqual "Dispatcher[akka.actor.default-dispatcher]" }
        a           <- withCtx { ctx =>
          ActorRefOf
            .fromActorRefFactory[F](ctx.actorRefFactory)
            .apply(TestActors.blackholeProps, "child".some)
            .allocated
        }
        (child0, childRelease) = a
        terminated1 <- probe.watch(child0)
        children    <- withCtx { _.children }
        _           <- Sync[F].delay { children should contain(child0) }
        child        = withCtx { _.child("child") }
        child1      <- child
        _           <- Sync[F].delay { child1 shouldEqual child0.some }
        _           <- childRelease
        _           <- terminated1
        child1      <- child
        _           <- Sync[F].delay { child1 shouldEqual none[ActorRef] }
        children    <- withCtx { _.children }
        _           <- Sync[F].delay { children should not contain child0 }
        identity    <- actorRef.ask(Identify("id"), timeout).flatten
        identity    <- identity.cast[F, ActorIdentity]
        _           <- withCtx { _.setReceiveTimeout(1.millis) }
        _           <- receiveTimeout
        _           <- Sync[F].delay { identity shouldEqual ActorIdentity("id", actorRef.toUnsafe.some) }
        seqNr       <- actorRef.ask(Cmd.Inc, timeout).flatten
        _            = seqNr shouldEqual 4
        a           <- actorRef.ask(Cmd.Stop, timeout).flatten
        _            = a shouldEqual "stopping"
        _           <- terminated0
      } yield {}
    }

    for {
      receiveTimeout <- Deferred[F, Unit]
      eventSourcedOf <- eventSourcedOf(receiveTimeout.complete(()))
        .typeless(_.cast[F, State], _.pure[F], _.cast[F, Event])
        .convert[Any, Any, Any, Any](_.pure[F], _.pure[F], _.pure[F], _.pure[F], _.pure[F], _.pure[F])
        .pure[F]
      actorRefOf      = ActorRefOf.fromActorRefFactory[F](actorSystem)
      probe           = Probe.of[F](actorRefOf)
      actorEffect     = PersistentActorEffect.of[F](actorRefOf, eventSourcedOf)
      resources       = (actorEffect, probe).tupled
      result         <- resources.use { case (actorEffect, probe) =>
        persistentActorOf(actorEffect, probe, receiveTimeout.get)
      }
    } yield result
  }


  private def `recover with empty state`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Int
    type C = Any
    type E = Any
    type R = Any

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit]
    ): EventSourcedOf[F, S, C, E, R] = {
      _: ActorCtx[F] => {
        val eventSourced: EventSourced[F, S, C, E, R] = new EventSourced[F, S, C, E, R] {

          def eventSourcedId = EventSourcedId("0")

          def pluginIds = PluginIds.empty

          def recovery = Recovery()

          def start = {
            val started = RecoveryStarted[F, S, C, E, R] { (_, snapshotOffer) =>
              val recovering: Recovering[F, S, C, E, R] = new Recovering[F, S, C, E, R] {

                def initial = snapshotOffer.fold(0) { _.snapshot }.pure[F]

                def replay = Replay1.empty[F, S, E].pure[Resource[F, *]]

                def completed(
                  seqNr: SeqNr,
                  state: S,
                  journaller: Journaller[F, E],
                  snapshotter: Snapshotter[F, S]
                ) = {
                  startedDeferred
                    .complete(())
                    .toResource
                    .as(Receive.empty[F, C, R])
                }
              }
              recovering.pure[Resource[F, *]]
            }
            Resource
              .make(().pure[F]) { _ => stoppedDeferred.complete(()) }
              .as(started)
          }
        }
        eventSourced.pure[F]
      }
    }

    for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E, R]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.cast[F, S], _.pure[F], _.pure[F])
        .pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _              <- actorEffect.use { _ => started.get }
      _              <- stopped.get
      actions        <- actions.get
      _               = actions.reverse shouldEqual List(
        Action.Created(EventSourcedId("0"), akka.persistence.Recovery(), PluginIds.empty),
        Action.Started,
        Action.RecoveryAllocated(None),
        Action.Initial(0),
        Action.ReceiveAllocated(0, 0L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
    } yield {}
  }


  private def `recover from snapshot`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Int
    type C = Any
    type E = Int
    type R = Any

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit]
    ): EventSourcedOf[F, S, C, E, R] = {
      _: ActorCtx[F] => {
        val eventSourced: EventSourced[F, S, C, E, R] = new EventSourced[F, S, C, E, R] {

          def eventSourcedId = EventSourcedId("1")

          def pluginIds = PluginIds.empty

          def recovery = Recovery()

          def start = {
            val started = RecoveryStarted[F, S, C, E, R] { (_, snapshotOffer) =>
              val recovering: Recovering[F, S, C, E, R] = new Recovering[F, S, C, E, R] {

                def initial = snapshotOffer.fold(0) { _.snapshot }.pure[F]

                def replay = Replay1.empty[F, S, E].pure[Resource[F, *]]

                def completed(
                  seqNr: SeqNr,
                  state: S,
                  journaller: Journaller[F, E],
                  snapshotter: Snapshotter[F, S]
                ) = {
                  val receive = for {
                    seqNr <- journaller.append(Nel.of(Nel.of(0))).flatten
                    _     <- snapshotter.save(seqNr, 1).flatten
                    _     <- startedDeferred.complete(())
                  } yield {
                    Receive.empty[F, C, R]
                  }
                  receive.toResource
                }
              }

              recovering.pure[Resource[F, *]]
            }

            Resource
              .make(().pure[F]) { _ => stoppedDeferred.complete(()) }
              .as(started)
          }
        }
        eventSourced.pure[F]
      }
    }

    def actions = for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E, R]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.cast[F, S], _.pure[F], _.cast[F, E])
        .pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _              <- actorEffect.use { _ => started.get }
      _              <- stopped.get
      actions        <- actions.get
    } yield {
      actions.reverse
    }

    for {
      saveSnapshot <- actions
      _ = saveSnapshot shouldEqual List(
        Action.Created(EventSourcedId("1"), akka.persistence.Recovery(), PluginIds.empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.Initial(0),
        Action.AppendEvents(Nel.of(Nel.of(0L))),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(1),
        Action.SaveSnapshot(1, 1),
        Action.SaveSnapshotOuter,
        Action.SaveSnapshotInner,
        Action.ReceiveAllocated(0, 0L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
      recover <- actions
      _ = recover shouldEqual List(
        Action.Created(EventSourcedId("1"), akka.persistence.Recovery(), PluginIds.empty),
        Action.Started,
        Action.RecoveryAllocated(SnapshotOffer(SnapshotMetadata("1", 1), 1).some),
        Action.Initial(1),
        Action.AppendEvents(Nel.of(Nel.of(0L))),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(2),
        Action.SaveSnapshot(2, 1),
        Action.SaveSnapshotOuter,
        Action.SaveSnapshotInner,
        Action.ReceiveAllocated(1, 1L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
    } yield {}
  }


  private def `recover from snapshot with deleted events`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Int
    type C = Any
    type E = Int
    type R = Any

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit]
    ): EventSourcedOf[F, S, C, E, R] = {
      _: ActorCtx[F] => {
        val eventSourced: EventSourced[F, S, C, E, R] = new EventSourced[F, S, C, E, R] {

          def eventSourcedId = EventSourcedId("6")

          def pluginIds = PluginIds.empty

          def recovery = Recovery()

          def start = {
            val started = RecoveryStarted[F, S, C, E, R] { (_, snapshotOffer) =>
              val recovering: Recovering[F, S, C, E, R] = new Recovering[F, S, C, E, R] {

                def initial = snapshotOffer.fold(0) { _.snapshot }.pure[F]

                def replay = Replay1.empty[F, S, E].pure[Resource[F, *]]

                def completed(
                  seqNr: SeqNr,
                  state: S,
                  journaller: Journaller[F, E],
                  snapshotter: Snapshotter[F, S]
                ) = {
                  val receive = for {
                    seqNr <- journaller.append(Nel.of(Nel.of(0))).flatten
                    _     <- snapshotter.save(seqNr, 1).flatten
                    seqNr <- journaller.append(Nel.of(Nel.of(1))).flatten
                    _     <- journaller.deleteTo(seqNr).flatten
                    _     <- startedDeferred.complete(())
                  } yield {
                    Receive.empty[F, C, R]
                  }
                  receive.toResource
                }
              }

              recovering.pure[Resource[F, *]]
            }

            Resource
              .make(().pure[F]) { _ => stoppedDeferred.complete(()) }
              .as(started)
          }
        }
        eventSourced.pure[F]
      }
    }

    def actions = for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E, R]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.cast[F, S], _.pure[F], _.cast[F, E])
        .pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _              <- actorEffect.use { _ => started.get }
      _              <- stopped.get
      actions        <- actions.get
    } yield {
      actions.reverse
    }

    for {
      saveSnapshot <- actions
      _ = saveSnapshot shouldEqual List(
        Action.Created(EventSourcedId("6"), akka.persistence.Recovery(), PluginIds.empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.Initial(0),
        Action.AppendEvents(Nel.of(Nel.of(0L))),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(1),
        Action.SaveSnapshot(1, 1),
        Action.SaveSnapshotOuter,
        Action.SaveSnapshotInner,
        Action.AppendEvents(Nel.of(Nel.of(1L))),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(2),
        Action.DeleteEventsTo(2),
        Action.DeleteEventsToOuter,
        Action.DeleteEventsToInner,
        Action.ReceiveAllocated(0, 0L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
      recover <- actions
      _ = recover shouldEqual List(
        Action.Created(EventSourcedId("6"), akka.persistence.Recovery(), PluginIds.empty),
        Action.Started,
        Action.RecoveryAllocated(SnapshotOffer(SnapshotMetadata("6", 1), 1).some),
        Action.Initial(1),
        Action.AppendEvents(Nel.of(Nel.of(0L))),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(3),
        Action.SaveSnapshot(3, 1),
        Action.SaveSnapshotOuter,
        Action.SaveSnapshotInner,
        Action.AppendEvents(Nel.of(Nel.of(1L))),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(4),
        Action.DeleteEventsTo(4),
        Action.DeleteEventsToOuter,
        Action.DeleteEventsToInner,
        Action.ReceiveAllocated(1, 2L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
    } yield {}
  }


  private def `recover from events`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Int
    type C = Any
    type E = Int
    type R = Any

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit]
    ): EventSourcedOf[F, S, C, E, R] = {
      _: ActorCtx[F] => {
        val eventSourced: EventSourced[F, S, C, E, R] = new EventSourced[F, S, C, E, R] {

          def eventSourcedId = EventSourcedId("2")

          def pluginIds = PluginIds.empty

          def recovery = Recovery()

          def start = {
            val started = RecoveryStarted[F, S, C, E, R] { (_, snapshotOffer) =>
              val recovering: Recovering[F, S, C, E, R] = new Recovering[F, S, C, E, R] {

                def initial = snapshotOffer.fold(0) { _.snapshot }.pure[F]

                def replay = {
                  val replay: Replay1[F, S, E] = (_, state, event) => (state + event).pure[F]
                  replay.pure[Resource[F, *]]
                }

                def completed(
                  seqNr: SeqNr,
                  state: S,
                  journaller: Journaller[F, E],
                  snapshotter: Snapshotter[F, S]
                ) = {
                  val receive = for {
                    _ <- journaller.append(Nel.of(Nel.of(0, 1), Nel.of(2))).flatten
                    _ <- startedDeferred.complete(())
                  } yield {
                    Receive.empty[F, C, R]
                  }
                  receive.toResource
                }
              }

              recovering.pure[Resource[F, *]]
            }

            Resource
              .make(().pure[F]) { _ => stoppedDeferred.complete(()) }
              .as(started)
          }
        }
        eventSourced.pure[F]
      }
    }


    def actions = for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E, R]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.cast[F, S], _.pure[F], _.cast[F, E])
        .pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _              <- actorEffect.use { _ => started.get }
      _              <- stopped.get
      actions        <- actions.get
    } yield {
      actions.reverse
    }

    for {
      appendEvents <- actions
      _ = appendEvents shouldEqual List(
        Action.Created(EventSourcedId("2"), akka.persistence.Recovery(), PluginIds.empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.Initial(0),
        Action.AppendEvents(Nel.of(Nel.of(0, 1), Nel.of(2))),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(3),
        Action.ReceiveAllocated(0, 0L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
      recover <- actions
      _ = recover shouldEqual List(
        Action.Created(EventSourcedId("2"), akka.persistence.Recovery(), PluginIds.empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.Initial(0),
        Action.ReplayAllocated,
        Action.Replayed(0, 0, 1, 0),
        Action.Replayed(0, 1, 2, 1),
        Action.Replayed(1, 2, 3, 3),
        Action.ReplayReleased,
        Action.AppendEvents(Nel.of(Nel.of(0, 1), Nel.of(2))),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(6),
        Action.ReceiveAllocated(3, 3L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
    } yield {}
  }


  private def `recover from events with deleted snapshot`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Int
    type C = Any
    type E = Int
    type R = Any

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit]
    ): EventSourcedOf[F, S, C, E, R] = {
      _: ActorCtx[F] => {
        val eventSourced: EventSourced[F, S, C, E, R] = new EventSourced[F, S, C, E, R] {

          def eventSourcedId = EventSourcedId("7")

          def pluginIds = PluginIds.empty

          def recovery = Recovery()

          def start = {
            val started = RecoveryStarted[F, S, C, E, R] { (_, snapshotOffer) =>
                val recovering: Recovering[F, S, C, E, R] = new Recovering[F, S, C, E, R] {

                  def initial = snapshotOffer.fold(0) { _.snapshot }.pure[F]

                  def replay = {
                    val replay: Replay1[F, S, E] = (_, state, event) => (state + event).pure[F]
                    replay.pure[Resource[F, *]]
                  }

                  def completed(
                    seqNr: SeqNr,
                    state: S,
                    journaller: Journaller[F, E],
                    snapshotter: Snapshotter[F, S]
                  ) = {
                    val receive = for {
                      seqNr <- journaller.append(Nel.of(Nel.of(0))).flatten
                      _     <- snapshotter.save(seqNr, 1).flatten
                      _     <- journaller.append(Nel.of(Nel.of(1))).flatten
                      _     <- snapshotter.delete(seqNr).flatten
                      _     <- startedDeferred.complete(())
                    } yield {
                      Receive.empty[F, C, R]
                    }
                    receive.toResource
                  }
                }

                recovering.pure[Resource[F, *]]
            }

            Resource
              .make(().pure[F]) { _ => stoppedDeferred.complete(()) }
              .as(started)
          }
        }
        eventSourced.pure[F]
      }
    }


    def actions = for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E, R]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.cast[F, S], _.pure[F], _.cast[F, E])
        .pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _              <- actorEffect.use { _ => started.get }
      _              <- stopped.get
      actions        <- actions.get
    } yield {
      actions.reverse
    }

    for {
      appendEvents <- actions
      _ = appendEvents shouldEqual List(
        Action.Created(EventSourcedId("7"), akka.persistence.Recovery(), PluginIds.empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.Initial(0),
        Action.AppendEvents(Nel.of(Nel.of(0))),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(1),
        Action.SaveSnapshot(1, 1),
        Action.SaveSnapshotOuter,
        Action.SaveSnapshotInner,
        Action.AppendEvents(Nel.of(Nel.of(1))),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(2),
        Action.DeleteSnapshot(1),
        Action.DeleteSnapshotOuter,
        Action.DeleteSnapshotInner,
        Action.ReceiveAllocated(0, 0L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
      recover <- actions
      _ = recover shouldEqual List(
        Action.Created(EventSourcedId("7"), akka.persistence.Recovery(), PluginIds.empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.Initial(0),
        Action.ReplayAllocated,
        Action.Replayed(0, 0, 1, 0),
        Action.Replayed(0, 1, 2, 1),
        Action.ReplayReleased,
        Action.AppendEvents(Nel.of(Nel.of(0))),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(3),
        Action.SaveSnapshot(3, 1),
        Action.SaveSnapshotOuter,
        Action.SaveSnapshotInner,
        Action.AppendEvents(Nel.of(Nel.of(1))),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(4),
        Action.DeleteSnapshot(3),
        Action.DeleteSnapshotOuter,
        Action.DeleteSnapshotInner,
        Action.ReceiveAllocated(1, 2L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
    } yield {}
  }


  private def `recover from snapshot and events`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Int
    type C = Any
    type E = Int
    type R = Any

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit]
    ): EventSourcedOf[F, S, C, E, R] = {
      _: ActorCtx[F] => {
        val eventSourced: EventSourced[F, S, C, E, R] = new EventSourced[F, S, C, E, R] {

          def eventSourcedId = EventSourcedId("3")

          def pluginIds = PluginIds.empty

          def recovery = Recovery()

          def start = {
            val started = RecoveryStarted[F, S, C, E, R] { (_, snapshotOffer) =>
              val recovering: Recovering[F, S, C, E, R] = new Recovering[F, S, C, E, R] {

                def initial = snapshotOffer.fold(0) { _.snapshot }.pure[F]

                def replay = {
                  val replay: Replay1[F, S, E] = (_, state, event) => (state + event).pure[F]
                  replay.pure[Resource[F, *]]
                }

                def completed(
                  seqNr: SeqNr,
                  state: S,
                  journaller: Journaller[F, E],
                  snapshotter: Snapshotter[F, S]
                ) = {
                  val receive = for {
                    seqNr <- journaller.append(Nel.of(Nel.of(0))).flatten
                    _     <- snapshotter.save(seqNr, 1).flatten
                    _     <- journaller.append(Nel.of(Nel.of(1))).flatten
                    _     <- startedDeferred.complete(())
                  } yield {
                    Receive.empty[F, C, R]
                  }

                  receive.toResource
                }
              }

              recovering.pure[Resource[F, *]]
            }

            Resource
              .make(().pure[F]) { _ => stoppedDeferred.complete(()) }
              .as(started)
          }
        }
        eventSourced.pure[F]
      }
    }

    def actions = for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E, R]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.cast[F, S], _.pure[F], _.cast[F, E])
        .pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _              <- actorEffect.use { _ => started.get }
      _              <- stopped.get
      actions        <- actions.get
    } yield {
      actions.reverse
    }

    for {
      write <- actions
      _ = write shouldEqual List(
        Action.Created(EventSourcedId("3"), akka.persistence.Recovery(), PluginIds.empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.Initial(0),
        Action.AppendEvents(Nel.of(Nel.of(0))),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(1),
        Action.SaveSnapshot(1, 1),
        Action.SaveSnapshotOuter,
        Action.SaveSnapshotInner,
        Action.AppendEvents(Nel.of(Nel.of(1))),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(2),
        Action.ReceiveAllocated(0, 0L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
      recover <- actions
      _ = recover shouldEqual List(
        Action.Created(EventSourcedId("3"), akka.persistence.Recovery(), PluginIds.empty),
        Action.Started,
        Action.RecoveryAllocated(SnapshotOffer(SnapshotMetadata("3", 1), 1).some),
        Action.Initial(1),
        Action.ReplayAllocated,
        Action.Replayed(1, 1, 2, 2),
        Action.ReplayReleased,
        Action.AppendEvents(Nel.of(Nel.of(0))),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(3),
        Action.SaveSnapshot(3, 1),
        Action.SaveSnapshotOuter,
        Action.SaveSnapshotInner,
        Action.AppendEvents(Nel.of(Nel.of(1))),
        Action.AppendEventsOuter,
        Action.AppendEventsInner(4),
        Action.ReceiveAllocated(2, 2L),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
    } yield {}
  }


  private def `start stops`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Any
    type C = Any
    type E = Any
    type R = Any

    def eventSourcedOf(
      lock: Deferred[F, Unit],
      stopped: Deferred[F, Unit]
    ): EventSourcedOf[F, S, C, E, R] = {
      actorCtx => {
        val eventSourced: EventSourced[F, S, C, E, R] = new EventSourced[F, S, C, E, R] {

          def eventSourcedId = EventSourcedId("id")

          def pluginIds = PluginIds.empty

          def recovery = Recovery()

          def start = {
            Resource
              .make(lock.get productR actorCtx.stop) { _ => stopped.complete(()) }
              .as(RecoveryStarted.empty[F, S, C, E, R](()))
          }
        }
        eventSourced.pure[F]
      }
    }

    for {
      lock           <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E, R]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(lock, stopped)).pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      actorEffect    <- actorEffect.allocated.map { case (actorEffect, _) => actorEffect }
      _              <- Probe.of(actorRefOf).use { probe =>
        for {
          terminated <- probe.watch(actorEffect.toUnsafe)
          _          <- lock.complete(())
          _          <- terminated
        } yield {}
      }
      _              <- stopped.get
      actions        <- actions.get
      _               = actions.reverse shouldEqual List(
        Action.Created(EventSourcedId("id"), akka.persistence.Recovery(), PluginIds.empty),
        Action.Started,
        Action.Released)
    } yield {}
  }


  private def `recoveryStarted stops`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Any
    type C = Any
    type E = Any
    type R = Any

    def eventSourcedOf(
      lock: Deferred[F, Unit],
      stopped: Deferred[F, Unit]
    ): EventSourcedOf[F, S, C, E, R] = {
      actorCtx => {
        val eventSourced: EventSourced[F, S, C, E, R] = new EventSourced[F, S, C, E, R] {

          def eventSourcedId = EventSourcedId("4")

          def pluginIds = PluginIds.empty

          def recovery = Recovery()

          def start = {
            val started = RecoveryStarted[F, S, C, E, R] { (_, _) =>
              Resource
                .make(lock.get productR actorCtx.stop) { _ => stopped.complete(()) }
                .as(Recovering.empty[F, S, C, E, R](()))
            }
            started.pure[Resource[F, *]]
          }
        }
        eventSourced.pure[F]
      }
    }

    for {
      lock           <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E, R]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(lock, stopped)).pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      actorEffect    <- actorEffect.allocated.map { case (actorEffect, _) => actorEffect }
      _              <- Probe.of(actorRefOf).use { probe =>
        for {
          terminated <- probe.watch(actorEffect.toUnsafe)
          _          <- lock.complete(())
          _          <- terminated
        } yield {}
      }
      _              <- stopped.get
      actions        <- actions.get
      _               = actions.reverse shouldEqual List(
        Action.Created(EventSourcedId("4"), akka.persistence.Recovery(), PluginIds.empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.Initial(()),
        Action.ReceiveAllocated((), 0),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
    } yield {}
  }


  private def `recoveryCompleted stops`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Unit
    type C = Any
    type E = Unit
    type R = Any

    def eventSourcedOf(
      lock: Deferred[F, Unit],
      stopped: Deferred[F, Unit]
    ): EventSourcedOf[F, S, C, E, R] = {
      actorCtx => {
        val eventSourced: EventSourced[F, S, C, E, R] = new EventSourced[F, S, C, E, R] {

          def eventSourcedId = EventSourcedId("5")

          def pluginIds = PluginIds.empty

          def recovery = Recovery()

          def start = {
            val started = RecoveryStarted[F, S, C, E, R] { (_, _) =>

              val recovering: Recovering[F, S, C, E, R] = new Recovering[F, S, C, E, R] {

                def initial = ().pure[F]

                def replay = Replay1.empty[F, S, E].pure[Resource[F, *]]

                def completed(
                  seqNr: SeqNr,
                  state: S,
                  journaller: Journaller[F, E],
                  snapshotter: Snapshotter[F, S]
                ) = {
                  Resource
                    .make(lock.get productR actorCtx.stop) { _ => stopped.complete(()) }
                    .as(Receive.empty[F, C, R])
                }
              }
              recovering.pure[Resource[F, *]]
            }
            started.pure[Resource[F, *]]
          }
        }
        eventSourced.pure[F]
      }
    }

    for {
      lock           <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E, R]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(lock, stopped))
        .typeless(_.cast[F, S], _.pure[F], _.cast[F, E])
        .pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      actorEffect    <- actorEffect.allocated.map { case (actorEffect, _) => actorEffect }
      _              <- Probe.of(actorRefOf).use { probe =>
        for {
          terminated <- probe.watch(actorEffect.toUnsafe)
          _          <- lock.complete(())
          _          <- terminated
        } yield {}
      }
      _              <- stopped.get
      actions        <- actions.get
      _               = actions.reverse shouldEqual List(
        Action.Created(EventSourcedId("5"), akka.persistence.Recovery(), PluginIds.empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.Initial(()),
        Action.ReceiveAllocated((), 0),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
    } yield {}
  }


  private def `append many`[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Boolean
    type C = Any
    type E = SeqNr
    type R = Any

    val events = Nel.fromListUnsafe((1L to 1000L).toList)

    def eventSourcedOf(
      startedDeferred: Deferred[F, Unit],
      stoppedDeferred: Deferred[F, Unit]
    ): EventSourcedOf[F, S, C, E, R] = {
      _: ActorCtx[F] => {
        val eventSourced: EventSourced[F, S, C, E, R] = new EventSourced[F, S, C, E, R] {

          def eventSourcedId = EventSourcedId("8")

          def pluginIds = PluginIds.empty

          def recovery = Recovery()

          def start = {
            val started = RecoveryStarted[F, S, C, E, R] { (_, _) =>

              val recovering: Recovering[F, S, C, E, R] = new Recovering[F, S, C, E, R] {

                def initial = true.pure[F]

                val replay = {
                  val replay: Replay1[F, S, E] = (_, _, _) => false.pure[F]
                  replay.pure[Resource[F, *]]
                }

                def completed(
                  seqNr: SeqNr,
                  state: S,
                  journaller: Journaller[F, E],
                  snapshotter: Snapshotter[F, S]
                ) = {

                  def append: F[Unit] = {
                    if (state) {
                      events
                        .traverse { event => journaller.append(Nel.of(Nel.of(event))) }
                        .flatMap { _.foldMapM { _.void } }
                    } else {
                      ().pure[F]
                    }
                  }

                  val receive = for {
                    _ <- append
                    _ <- startedDeferred.complete(())
                  } yield {
                    Receive.empty[F, C, R]
                  }
                  receive.toResource
                }
              }
              recovering.pure[Resource[F, *]]
            }
            Resource
              .make(().pure[F]) { _ => stoppedDeferred.complete(()) }
              .as(started)
          }
        }
        eventSourced.pure[F]
      }
    }

    def actions = for {
      started        <- Deferred[F, Unit]
      stopped        <- Deferred[F, Unit]
      actions        <- Ref[F].of(List.empty[Action[S, C, E, R]])
      eventSourcedOf <- InstrumentEventSourced(actions, eventSourcedOf(started, stopped))
        .typeless(_.cast[F, S], _.pure[F], _.cast[F, E])
        .pure[F]
      actorEffect     = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      _              <- actorEffect.use { _ => started.get }
      _              <- stopped.get
      actions        <- actions.get
    } yield {
      actions.reverse
    }

    def appends: Nel[Action[S, C, E, R]] = {
      val appendEvents: Nel[Action[S, C, E, R]] = events.flatMap { event =>
        Nel.of(
          Action.AppendEvents(Nel.of(Nel.of(event))),
          Action.AppendEventsOuter)
      }
      val appendEventsInner: Nel[Action[S, C, E, R]] = events.map { event =>
        Action.AppendEventsInner(event)
      }

      appendEvents ::: appendEventsInner
    }

    def replayed: Nel[Action[S, C, E, R]] = {
      events.map { event => Action.Replayed(event == 1, event, event, false) }
    }

    for {
      a <- actions
      _  = a shouldEqual List(
        Action.Created(EventSourcedId("8"), akka.persistence.Recovery(), PluginIds.empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.Initial(true)) ++
      appends.toList ++
      List(
        Action.ReceiveAllocated(true, 0),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
      a <- actions
      _  = a shouldEqual List(
        Action.Created(EventSourcedId("8"), akka.persistence.Recovery(), PluginIds.empty),
        Action.Started,
        Action.RecoveryAllocated(none),
        Action.Initial(true),
        Action.ReplayAllocated) ++
      replayed.toList ++
      List(
        Action.ReplayReleased,
        Action.ReceiveAllocated(false, events.last),
        Action.ReceiveReleased,
        Action.RecoveryReleased,
        Action.Released)
    } yield {}
  }


  private def setReceiveTimeout[F[_]: Concurrent: Timer: ToFuture: FromFuture: ToTry](actorSystem: ActorSystem) = {

    val actorRefOf = ActorRefOf.fromActorRefFactory[F](actorSystem)

    type S = Boolean
    type C = Any
    type E = SeqNr
    type R = Any

    def eventSourcedOf(timedOut: Deferred[F, Unit]): EventSourcedOf[F, S, C, E, R] = {
      actorCtx: ActorCtx[F] => {
        for {
          _ <- actorCtx.setReceiveTimeout(10.millis)
        } yield {
          new EventSourced[F, S, C, E, R] {

            def eventSourcedId = EventSourcedId("9")

            def pluginIds = PluginIds.empty

            def recovery = Recovery()

            def start = {
              for {
                _ <- actorCtx.setReceiveTimeout(10.millis).toResource
              } yield {
                RecoveryStarted[F, S, C, E, R] { (_, _) =>
                  for {
                    _ <- actorCtx.setReceiveTimeout(10.millis).toResource
                  } yield {
                    new Recovering[F, S, C, E, R] {

                      def initial = true.pure[F]

                      val replay = {
                        Replay1
                          .const[F, S, E](false.pure[F])
                          .pure[Resource[F, *]]
                      }

                      def completed(
                        seqNr: SeqNr,
                        state: S,
                        journaller: Journaller[F, E],
                        snapshotter: Snapshotter[F, S]
                      ) = {
                        for {
                          _ <- actorCtx.setReceiveTimeout(10.millis).toResource
                        } yield {
                          Receive[F, C, R] { (a, _) =>
                            a match {
                              case ReceiveTimeout => timedOut.complete(()).as(true)
                              case _              => false.pure[F]
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

    for {
      timedOut       <- Deferred[F, Unit]
      eventSourcedOf <- eventSourcedOf(timedOut)
        .typeless(_.cast[F, S], _.pure[F], _.cast[F, E])
        .pure[F]
      result          = PersistentActorEffect.of(actorRefOf, eventSourcedOf)
      result         <- result.use { _ => timedOut.get}
    } yield result
  }
}