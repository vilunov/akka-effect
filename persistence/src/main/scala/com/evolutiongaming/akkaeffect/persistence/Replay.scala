package com.evolutiongaming.akkaeffect.persistence

import cats.implicits._
import cats.{Applicative, FlatMap}
import com.evolutiongaming.akkaeffect.Convert
import com.evolutiongaming.akkaeffect.Convert.implicits._

trait Replay[F[_], S, E] {

  def apply(state: S, event: E, seqNr: SeqNr): F[S]
}

object Replay {

  def const[F[_], S, E](state: F[S]): Replay[F, S, E] = (_: S, _: E, _: SeqNr) => state

  def empty[F[_] : Applicative, S, E]: Replay[F, S, E] = (state: S, _: E, _: SeqNr) => state.pure[F]


  implicit class ReplayOps[F[_], S, E](val self: Replay[F, S, E]) extends AnyVal {

    def untyped(implicit
      F: FlatMap[F],
      anyToS: Convert[F, Any, S],
      anyToE: Convert[F, Any, E]
    ): Replay[F, Any, Any] = {

      (state: Any, event: Any, seqNr: SeqNr) => {
        for {
          state <- state.convert[F, S]
          event <- event.convert[F, E]
          state <- self(state, event, seqNr)
        } yield state
      }
    }
  }
}