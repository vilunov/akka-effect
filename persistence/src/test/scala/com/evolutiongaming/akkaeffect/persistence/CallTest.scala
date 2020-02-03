package com.evolutiongaming.akkaeffect.persistence

import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.implicits._
import com.evolutiongaming.akkaeffect.IOSuite._
import com.evolutiongaming.akkaeffect.{Act, ActorSuite}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.util.control.NoStackTrace

class CallTest extends AsyncFunSuite with ActorSuite with Matchers {

  test("adapter") {

    case class Msg(key: Int, result: IO[String])

    val error = new RuntimeException with NoStackTrace

    val result = for {
      ref     <- Ref[IO].of(0)
      stopped  = ref.update { _ + 1 }.as("stopped")
      a       <- Call
        .adapter[IO, Int, String](Act.now, stopped) { case Msg(a, b) => (a, b) }
        .use { call =>
          for {
            a0 <- call.value { 0 }
            a1 <- call.value { 1 }
            a2 <- call.value { 2 }
            a3 <- call.value { 3 }
            _  <- IO.delay { call.receive.lift(Msg(0, "0".pure[IO])) }
            _  <- IO.delay { call.receive.lift(Msg(1, "1".pure[IO])) }
            _  <- IO.delay { call.receive.lift(Msg(2, error.raiseError[IO, String])) }
            _  <- IO.delay { call.receive.lift(Msg(2, "2".pure[IO])) }
            a  <- a0
            _   = a shouldEqual "0"
            a  <- a1
            _   = a shouldEqual "1"
            a  <- a2.attempt
            _   = a shouldEqual error.asLeft
          } yield a3
        }
      a       <- a
      _        = a shouldEqual "stopped"
    } yield {}
    result.run()
  }
}
