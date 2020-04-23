package com.evolutiongaming.akkaeffect.eventsourcing

import cats.effect.{Concurrent, Resource}
import cats.implicits._
import com.evolutiongaming.akkaeffect.AkkaEffectHelper._
import com.evolutiongaming.akkaeffect.Receive
import com.evolutiongaming.akkaeffect.persistence.{Append, SeqNr}
import com.evolutiongaming.catshelper.{FromFuture, ToFuture}

// TODO store snapshot in scope of persist queue or expose seqNr
// TODO expose dropped commands because of stop, etc
object ReceiveFromReceiveCmd {

  def apply[F[_]: Concurrent: ToFuture: FromFuture, S, C, E, R](
    state: S,
    seqNr: SeqNr,
    append: Append[F, E],
    receiveCmd: ReceiveCmd[F, S, C, E]
  ): Resource[F, Receive[F, C, R]] = {

    Engine
      .of(Engine.State(state, seqNr), append)
      .map { engine =>
        Receive[F, C, R] { (msg, _, _) =>
          val result = for {
            validate <- receiveCmd(msg)
            result   <- engine(validate)
            result   <- result
          } yield result
          result.startNow.as(false) // TODO wrong
        }
      }
  }
}
