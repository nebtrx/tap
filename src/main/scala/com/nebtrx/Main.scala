package com.nebtrx

import cats.effect.ExitCase.{Canceled, Completed, Error}
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._

object Main extends App {
  println("Hello " |+| "Cats!")
}


trait Tap[F[_]] {
  def apply[A](effect: F[A]): F[A]
}

object Tap {
  type Percentage = Double

  case class TapState(totalRequest: Long, failedRequests: Long) {
    def logRequestWithResult(success: Boolean): TapState =
      this.copy(
        totalRequest = this.totalRequest + 1,
        failedRequests = if (success) this.failedRequests else failedRequests + 1)
  }

  object TapState {
    def empty = TapState(0, 0)
  }

  def make[F[_]: Sync](
                  errBound: Percentage,
                  qualified: Throwable => Boolean,
                  rejected: => Throwable): F[Tap[F]] = for {
    r <- Ref.of(TapState.empty)

  } yield new TapImpl[F](r, errBound, qualified, rejected)


  private class TapImpl[F[_] : Sync](stateRef: Ref[F, TapState],
                             errBound: Percentage,
                             qualified: Throwable => Boolean,
                             rejected: => Throwable) extends Tap[F] {
    override def apply[A](effect: F[A]): F[A] = {
      for {
        state <- stateRef.get
        failed = state.failedRequests
        failsAllowedRate: Percentage = state.totalRequest * errBound.toDouble

        result <- if (failed < failsAllowedRate) {
          Sync[F].guaranteeCase(effect) {
            case Completed => logRequestResultInRefState(stateRef, success = true)
            case Error(e) => logRequestResultInRefState(stateRef, qualified(e))
            case Canceled => logRequestResultInRefState(stateRef, success = false)
          }
        } else {
          stateRef.update(_.logRequestWithResult(true)) >> Sync[F].raiseError(rejected)
        }
      } yield result
    }
  }

  private def logRequestResultInRefState[F[_]](stateRef: Ref[F, TapState], success: Boolean): F[Unit] = {
    stateRef.update(_.logRequestWithResult(success))
  }
}
