package com.evolutiongaming.akkaeffect.persistence

import akka.actor.{ActorIdentity, ActorRef, ActorSystem, Identify, ReceiveTimeout}
import akka.testkit.TestActors
import cats.data.{NonEmptyList => Nel}
import cats.effect.concurrent.{Deferred, Ref}
import cats.effect.{Concurrent, IO, Resource, Sync}
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect._
import com.evolutiongaming.akkaeffect.persistence.InstrumentPersistenceSetup.Action
import com.evolutiongaming.catshelper.{FromFuture, ToFuture, ToTry}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.control.NoStackTrace

class PersistentActorOfSpec extends AsyncFunSuite with ActorSuite with Matchers {
  import PersistentActorOfSpec._

  test("PersistentActor") {
    `persistentActorOf`[IO](actorSystem).run()
  }

  test("recover with empty state") {
    `recover with empty state`(actorSystem).run()
  }

  test("recover from snapshot") {
    `recover from snapshot`(actorSystem).pure[IO].run()
  }

  test("recover from events") {
    `recover from events`(actorSystem).run()
  }

  test("recover from snapshot and  events") {
    `recover from snapshot and  events`(actorSystem).run()
  }

  test("append events") {
    ().pure[IO].run()
  }

  private def `persistentActorOf`[F[_] : Concurrent : ToFuture : FromFuture : ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {

    type State = Int
    type Event = String

    sealed trait Cmd

    object Cmd {
      case object Inc extends Cmd
      case object Stop extends Cmd
      final case class WithCtx[A](f: ActorCtx[F, Any, Any] => F[A]) extends Cmd
    }

    def persistenceSetupOf(receiveTimeout: F[Unit]): PersistenceSetupOf[F, Any, Any, Any, Any] = {
      ctx: ActorCtx[F, Any, Any] => {

        val persistenceSetup = new PersistenceSetup[F, State, Any, Event, Any] {

          def persistenceId = "persistenceId"

          def recoveryStarted(
            snapshotOffer: Option[SnapshotOffer[State]],
            journaller: Journaller[F, Event],
            snapshotter: Snapshotter[F, State]
          ) = {

            val recovering: Recovering[F, State, Any, Event, Any] = new Recovering[F, State, Any, Event, Any] {

              def initial = 0

              val replay = new Replay[F, State, Event] {

                def apply(state: State, event: Event, seqNr: SeqNr) = {
                  println(s"replay state: $state, event: $event, seqNr: $seqNr")
                  state.pure[F]
                }
              }

              def recoveryCompleted(state: State, seqNr: SeqNr) = {
                println(s"onRecoveryCompleted state: $state, seqNr: $seqNr")

                for {
                  stateRef <- Ref[F].of(state)
                } yield {
                  new Receive[F, Any, Any] {

                    def apply(cmd: Any, reply: Reply[F, Any]) = {
                      cmd match {
                        case a: Cmd.WithCtx[_] =>
                          for {
                            a <- a.f(ctx)
                            _ <- reply(a)
                          } yield false

                        case ReceiveTimeout =>
                          for {
                            _ <- ctx.setReceiveTimeout(Duration.Inf)
                            _ <- receiveTimeout
                          } yield false

                        case Cmd.Inc =>
                          for {
                            _      <- journaller.append(Nel.of(Nel.of("a"))).flatten
                            _      <- stateRef.update { _ + 1 }
                            state  <- stateRef.get
                            result <- snapshotter.save(state)
                            seqNr  <- journaller.append(Nel.of(Nel.of("b"), Nel.of("c", "d")))
                            seqNr  <- seqNr
                            _      <- result.done
                            _      <- stateRef.update { _ + 1 }
                            _      <- reply(seqNr)
                          } yield false

                        case Cmd.Stop =>
                          for {
                            _ <- reply("stopping")
                          } yield true

                        case _ => Error(s"unexpected $cmd").raiseError[F, Boolean]
                      }
                    }
                  }
                }
              }
            }
            recovering.pure[Resource[F, *]]
          }
        }

        implicit val anyToEvent = Convert.cast[F, Any, Event]

        implicit val anyToState = Convert.cast[F, Any, State]

        persistenceSetup.untyped.pure[F]
      }
    }

    def persistentActorOf(
      actorRef: ActorEffect[F, Any, Any],
      probe: Probe[F],
      receiveTimeout: F[Unit],
      actorRefOf: ActorRefOf[F]
    ): F[Unit] = {

      val timeout = 10.seconds

      def withCtx[A: ClassTag](f: ActorCtx[F, Any, Any] => F[A]): F[A] = {
        for {
          a <- actorRef.ask(Cmd.WithCtx(f), timeout)
          a <- a.cast[F, A]
        } yield a
      }

      for {
        terminated0 <- probe.watch(actorRef.toUnsafe)
        dispatcher  <- withCtx { _.dispatcher.pure[F] }
        _           <- Sync[F].delay { dispatcher.toString shouldEqual "Dispatcher[akka.actor.default-dispatcher]" }
        a           <- withCtx { _.actorRefOf(TestActors.blackholeProps, "child".some).allocated }
        (child0, childRelease) = a
        terminated1 <- probe.watch(child0)
        children    <- withCtx { _.children }
        _           <- Sync[F].delay { children.toList shouldEqual List(child0) }
        child        = withCtx { _.child("child") }
        child1      <- child
        _           <- Sync[F].delay { child1 shouldEqual child0.some }
        _           <- childRelease
        _           <- terminated1
        child1      <- child
        _           <- Sync[F].delay { child1 shouldEqual none[ActorRef] }
        children    <- withCtx { _.children }
        _           <- Sync[F].delay { children.toList shouldEqual List.empty }
        identity    <- actorRef.ask(Identify("id"), timeout)
        identity    <- identity.cast[F, ActorIdentity]
        _           <- withCtx { _.setReceiveTimeout(1.millis) }
        _           <- receiveTimeout
        _           <- Sync[F].delay { identity shouldEqual ActorIdentity("id", actorRef.toUnsafe.some) }
        seqNr       <- actorRef.ask(Cmd.Inc, timeout)
        _           <- Sync[F].delay { seqNr shouldEqual 4 }
        a           <- actorRef.ask(Cmd.Stop, timeout)
        _           <- Sync[F].delay { a shouldEqual "stopping" }
        _           <- terminated0
      } yield {}
    }

    for {
      receiveTimeout     <- Deferred[F, Unit]
      persistenceSetupOf <- persistenceSetupOf(receiveTimeout.complete(())).pure[F]
      actorRefOf          = ActorRefOf[F](actorSystem)
      probe               = Probe.of[F](actorRefOf)
      actorEffect         = PersistentActorEffect.of[F](actorRefOf, persistenceSetupOf)
      resources           = (actorEffect, probe).tupled
      result             <- resources.use { case (actorEffect, probe) =>
        persistentActorOf(actorEffect, probe, receiveTimeout.get, actorRefOf)
      }
    } yield result
  }


  private def `recover with empty state`[F[_] : Concurrent : ToFuture : FromFuture : ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    val actorRefOf = ActorRefOf[F](actorSystem)

    type S = Int
    type C = Any
    type E = Any
    type R = Any

    def persistenceSetupOf(
      started: Deferred[F, Unit],
      stopped: Deferred[F, Unit]
    ): PersistenceSetupOf[F, S, C, E, R] = {
      ctx: ActorCtx[F, C, R] => {
        val persistenceSetup: PersistenceSetup[F, S, C, E, R] = new PersistenceSetup[F, S, C, E, R] {

          def persistenceId = "0"

          def recoveryStarted(
            snapshotOffer: Option[SnapshotOffer[S]],
            journaller: Journaller[F, E],
            snapshotter: Snapshotter[F, S]
          ) = {
            val recovering: Recovering[F, S, C, E, R] = new Recovering[F, S, C, E, R] {

              def initial = snapshotOffer.fold(0) { _.snapshot }

              def replay = Replay.empty[F, S, E]

              def recoveryCompleted(state: S, seqNr: SeqNr) = {
                for {
                  _ <- started.complete(())
                } yield {
                  Receive.empty[F, C, R]
                }
              }
            }

            for {
              _ <- Resource.make(().pure[F]) { _ => stopped.complete(()) }
            } yield {
              recovering
            }
          }
        }
        persistenceSetup.pure[F]
      }
    }

    implicit val anyS = Convert.cast[F, Any, S]


    for {
      started            <- Deferred[F, Unit]
      stopped            <- Deferred[F, Unit]
      actions            <- Ref[F].of(List.empty[Action[S, C, E, R]])
      persistenceSetupOf <- InstrumentPersistenceSetup(actions, persistenceSetupOf(started, stopped)).pure[F]
      actorEffect         = PersistentActorEffect.of(actorRefOf, persistenceSetupOf.convert)
      _                  <- actorEffect.use { _ => started.get }
      _                  <- stopped.get
      actions            <- actions.get
      _                   = actions.reverse shouldEqual List(
        Action.Created("0", akka.persistence.Recovery(), PluginIds.default),
        Action.RecoveryAllocated(None,0),
        Action.ReceiveAllocated(0, 0L),
        Action.RecoveryReleased)
    } yield {}
  }


  private def `recover from snapshot`[F[_] : Concurrent : ToFuture : FromFuture : ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    ().pure[F]
  }


  private def `recover from events`[F[_] : Concurrent : ToFuture : FromFuture : ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    ().pure[F]
  }


  private def `recover from snapshot and  events`[F[_] : Concurrent : ToFuture : FromFuture : ToTry](
    actorSystem: ActorSystem
  ): F[Unit] = {
    ().pure[F]
  }
}

object PersistentActorOfSpec {


  implicit class AnyOps[A](val self: A) extends AnyVal {

    // TODO move out
    def cast[F[_] : Sync, B <: A](implicit tag: ClassTag[B]): F[B] = {
      def error = new ClassCastException(s"${ self.getClass.getName } cannot be cast to ${ tag.runtimeClass.getName }")

      tag.unapply(self) match {
        case Some(a) => a.pure[F]
        case None    => error.raiseError[F, B]
      }
    }
  }

  case class Error(msg: String) extends RuntimeException(msg) with NoStackTrace
}
