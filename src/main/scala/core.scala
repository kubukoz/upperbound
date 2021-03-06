package upperbound

import fs2.{async, Pipe, Stream, Scheduler}
import cats.effect.Effect

import cats.syntax.functor._
import cats.syntax.apply._
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.applicativeError._
import cats.syntax.monadError._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

import queues.Queue

object core {

  /**
    * A purely functional worker which submits jobs to the rate
    * limiter for asynchronous execution
    */
  trait Worker[F[_]] {

    /**
      * Returns an `F[Unit]` which represents the action of submitting
      * `job` to the [[Limiter]], with the given priority. A higher
      * number means a higher priority. The default is 0.
      *
      * The semantics of `submit` are fire-and-forget: the returned
      * `F[Unit]` immediately returns, without waiting for the `F[A]`
      * to complete its execution. Note that this means that the
      * returned `F[Unit]` will be successful regardless of whether
      * `job` fails or not.
      *
      * This method is designed to be called concurrently: every
      * concurrent call submits a job, and they are all started at a
      * rate which is no higher then the maximum rate you specify when
      * constructing a [[Limiter]]. Higher priority jobs take precedence
      * over lower priority ones.
      *
      * The `ack` parameter can be used to signal to the
      * [[Limiter]] that backpressure should be applied depending on the
      * result of `job`. Note that this will only change the
      * processing rate: it won't do any error handling for you. Also
      * see [[BackPressure]]
      */
    def submit[A](job: F[A],
                  priority: Int = 0,
                  ack: BackPressure.Ack[A] = BackPressure.never[A]): F[Unit]

    /**
      * Returns an `F[A]` which represents the action of submitting
      * `fa` to the [[Limiter]] with the given priority, and waiting for
      * its result. A higher number means a higher priority. The
      * default is 0.
      *
      * The semantics of `await` are blocking: the returned `F[A]`
      * only completes when `job` has finished its execution,
      * returning the result of `job` or failing with the same error
      * `job` failed with. However, the blocking is only semantic, no
      * actual threads are blocked by the implementation.
      *
      * This method is designed to be called concurrently: every
      * concurrent call submits a job, and they are all started at a
      * rate which is no higher then the maximum rate you specify when
      * constructing a [[Limiter]].
      *
      * The `ack` parameter can be used to signal to the
      * [[Limiter]] that backpressure should be applied depending on the
      * result of `job`. Note that this will only change the
      * processing rate: it won't do any error handling for you. Also
      * see [[BackPressure]]
      */
    def await[A](job: F[A],
                 priority: Int = 0,
                 ack: BackPressure.Ack[A] = BackPressure.never[A]): F[A]

    /**
      * Obtains a snapshot of the current number of jobs waiting to be
      * executed. May be out of date the instant after it is
      * retrieved.
      */
    def pending: F[Int]
  }

  object Worker {

    def create[F[_]](backend: Queue[F, F[BackPressure]])(
        implicit F: Effect[F],
        ec: ExecutionContext): Worker[F] = new Worker[F] {

      def submit[A](job: F[A],
                    priority: Int,
                    ack: BackPressure.Ack[A]): F[Unit] =
        backend.enqueue(
          job.attempt map ack,
          priority
        )

      def await[A](job: F[A], priority: Int, ack: BackPressure.Ack[A]): F[A] =
        async.promise[F, Either[Throwable, A]] flatMap { p =>
          backend.enqueue(
            job.attempt.flatTap(p.complete).map(ack),
            priority
          ) *> p.get.rethrow
        }

      def pending: F[Int] = backend.size
    }

    /**
      * Creates a noOp worker, with no rate limiting and a synchronous
      * `submit` method. `pending` is always zero.
      */
    def noOp[F[_]: Effect](implicit ec: ExecutionContext): Worker[F] =
      new Worker[F] {
        def submit[A](job: F[A],
                      priority: Int,
                      ack: BackPressure.Ack[A]): F[Unit] = job.void

        def await[A](job: F[A],
                     priority: Int = 0,
                     ack: BackPressure.Ack[A]): F[A] = job

        def pending: F[Int] = 0.pure[F]
      }
  }

  /**
    * A purely functional rate limiter. Used to manage the lifetime of a [[Worker]]
    */
  trait Limiter[F[_]] {
    def worker: Worker[F]
    def shutDown: F[Unit]
  }

  object Limiter {

    /**
      * Creates a new [[Limiter]], and concurrently starts processing the
      * jobs submitted by the corresponding [[Worker]], which are
      * started at a rate no higher than `1 / period`
      *
      * Every time a job signals backpressure is needed, the [[Limiter]]
      * will adjust its current rate by applying `backOff` to it. This
      * means the rate will be adjusted by calling `backOff`
      * repeatedly whenever multiple consecutive jobs signal for
      * backpressure, and reset to its original value when a job
      * signals backpressure is no longer needed.
      *
      * Note that since jobs submitted to the limiter are processed
      * asynchronously, rate changes might not propagate instantly when
      * the rate is smaller than the job completion time. However, the
      * rate will eventually converge to its most up-to-date value.
      *
      * Similarly, `n` allows you to place a bound on the maximum
      * number of jobs allowed to queue up while waiting for
      * execution. Once this number is reached, the `F` returned by
      * any call to the corresponding [[Worker]] will immediately fail
      * with a [[LimitReachedException]], so that you can in turn
      * signal for backpressure downstream. Processing restarts as
      * soon as the number of jobs waiting goes below `n` again.
      */
    def start[F[_]: Effect](
        period: FiniteDuration,
        backOff: FiniteDuration => FiniteDuration,
        n: Int)(implicit ec: ExecutionContext): F[Limiter[F]] =
      Scheduler.allocate[F](2) flatMap { sch =>
        Queue.bounded[F, F[BackPressure]](n) flatMap { queue =>
          async.signalOf[F, Boolean](false) flatMap { stop =>
            async.refOf[F, FiniteDuration](period) flatMap { interval =>
              val (scheduler, cleanup) = sch
              // `job` needs to be executed asynchronously so that long
              // running jobs don't interfere with the frequency of pulling
              // from the queue. It also means that a failed `job` doesn't
              // cause the overall processing to fail
              def exec(job: F[BackPressure]): F[Unit] =
                async.fork {
                  job.map(_.slowDown) ifM (
                    ifTrue = interval.modify(backOff).void,
                    ifFalse = interval.setSync(period)
                  )
                }

              def rate[A]: Pipe[F, A, A] =
                in =>
                  Stream
                    .eval(interval.get)
                    .flatMap(scheduler.sleep[F])
                    .repeat
                    .zip(in)
                    .map(_._2)

              def executor: Stream[F, Unit] =
                queue.dequeueAll
                  .through(rate)
                  .evalMap(exec)
                  .interruptWhen(stop)

              async.fork(executor.compile.drain) as {
                new Limiter[F] {
                  def worker = Worker.create(queue)
                  def shutDown = stop.set(true) *> cleanup
                }
              }
            }
          }
        }
      }
  }
}
