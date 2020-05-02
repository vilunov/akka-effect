package com.evolutiongaming.akkaeffect.persistence

import cats.Monad
import cats.effect.Resource
import cats.implicits._
import com.evolutiongaming.catshelper.CatsHelper._

/**
  * Describes "RecoveryStarted" phase
  *
  * @tparam S snapshot
  * @tparam C command
  * @tparam E event
  * @tparam R reply
  */
trait RecoveryStarted[F[_], S, C, E, R] {

  /**
    * Called upon starting recovery, resource will be released upon actor termination
    *
    * @see [[akka.persistence.SnapshotOffer]]
    */
  def apply(
    seqNr: SeqNr,
    snapshotOffer: Option[SnapshotOffer[S]]
  ): Resource[F, Recovering[F, S, C, E, R]]
}

object RecoveryStarted {

  def apply[F[_], S, C, E, R](
    f: (SeqNr, Option[SnapshotOffer[S]]) => Resource[F, Recovering[F, S, C, E, R]]
  ): RecoveryStarted[F, S, C, E, R] = {
    (seqNr, snapshotOffer) => f(seqNr, snapshotOffer)
  }

  def const[F[_], S, C, E, R](
    recovering: Resource[F, Recovering[F, S, C, E, R]]
  ): RecoveryStarted[F, S, C, E, R] = {
    (_, _) => recovering
  }

  def empty[F[_]: Monad, S, C, E, R](state: S): RecoveryStarted[F, S, C, E, R] = {
    const(Recovering.empty[F, S, C, E, R](state).pure[Resource[F, *]])
  }


  implicit class RecoveryStartedOps[F[_], S, C, E, R](
    val self: RecoveryStarted[F, S, C, E, R]
  ) extends AnyVal {

    def convert[S1, C1, E1, R1](
      sf: S => F[S1],
      s1f: S1 => F[S],
      cf: C1 => F[C],
      ef: E => F[E1],
      e1f: E1 => F[E],
      rf: R => F[R1])(implicit
      F: Monad[F],
    ): RecoveryStarted[F, S1, C1, E1, R1] = {

      (seqNr, snapshotOffer) => {

        val snapshotOffer1 = snapshotOffer.traverse { snapshotOffer =>
          s1f(snapshotOffer.snapshot).map { snapshot => snapshotOffer.as(snapshot) }
        }

        for {
          snapshotOffer <- snapshotOffer1.toResource
          recovering    <- self(seqNr, snapshotOffer)
        } yield {
          recovering.convert(sf, s1f, cf, ef, e1f, rf)
        }
      }
    }


    def widen[S1 >: S, C1 >: C, E1 >: E, R1 >: R](
      sf: S1 => F[S],
      cf: C1 => F[C],
      ef: E1 => F[E])(implicit
      F: Monad[F],
    ): RecoveryStarted[F, S1, C1, E1, R1] = {
      (seqNr, snapshotOffer) => {

        val snapshotOffer1 = snapshotOffer.traverse { snapshotOffer =>
          sf(snapshotOffer.snapshot).map { snapshot => snapshotOffer.copy(snapshot = snapshot) }
        }

        for {
          snapshotOffer <- snapshotOffer1.toResource
          recovering    <- self(seqNr, snapshotOffer)
        } yield {
          recovering.widen(sf, cf, ef)
        }
      }
    }


    def typeless(
      sf: Any => F[S],
      cf: Any => F[C],
      ef: Any => F[E])(implicit
      F: Monad[F],
    ): RecoveryStarted[F, Any, Any, Any, Any] = widen[Any, Any, Any, Any](sf, cf, ef)
  }
}
