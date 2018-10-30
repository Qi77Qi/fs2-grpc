package org.lyranthe.fs2_grpc
package java_runtime
package client

import cats.effect._
import cats.effect.concurrent.{Deferred, Ref}
import cats.implicits._
import io.grpc.{Metadata, _}
import fs2._

final case class UnaryResult[A](value: Option[A], status: Option[GrpcStatus])
final case class GrpcStatus(status: Status, trailers: Metadata)

class Fs2ClientCall[F[_], Request, Response] private[client] (val call: ClientCall[Request, Response],
                                                              val wakeOnReady: Ref[F, Option[Deferred[F, Unit]]]) {
  def onReady()(implicit F: Sync[F]): F[Unit] = {
    wakeOnReady
      .modify({
        case None       => (None, F.unit)
        case Some(wake) => (None, wake.complete(()))
      })
      .flatten
  }

  private def isReady(implicit F: Sync[F]): F[Boolean] =
    F.delay(call.isReady)

  private def cancel(message: Option[String], cause: Option[Throwable])(implicit F: Sync[F]): F[Unit] =
    F.delay(call.cancel(message.orNull, cause.orNull))

  private def halfClose(implicit F: Sync[F]): F[Unit] =
    F.delay(call.halfClose())

  private def request(numMessages: Int)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.request(numMessages))

  private def sendMessage(message: Request)(implicit F: Concurrent[F]): F[Unit] = {
    F.delay(call.sendMessage(message))
  }

  private def sendMessageOrDelay(message: Request)(implicit F: Concurrent[F]): F[Unit] = {
    isReady.ifM(
      sendMessage(message), {
        Deferred[F, Unit].flatMap { wakeup =>
          wakeOnReady.set(wakeup.some) *>
            isReady.ifM(sendMessage(message), wakeup.get *> sendMessage(message))
        }
      }
    )
  }

  private def start(listener: ClientCall.Listener[Response], metadata: Metadata)(implicit F: Sync[F]): F[Unit] =
    F.delay(call.start(listener, metadata))

  def startListener[A <: ClientCall.Listener[Response]](createListener: F[A], headers: Metadata)(
      implicit F: Sync[F]): F[A] = {
    createListener.flatTap(start(_, headers)) <* request(1)
  }

  def sendSingleMessage(message: Request)(implicit F: Concurrent[F]): F[Unit] = {
    sendMessage(message) *> halfClose
  }

  def sendStream(stream: Stream[F, Request])(implicit F: Concurrent[F]): Stream[F, Unit] = {
    stream.evalMap(sendMessageOrDelay) ++ Stream.eval(halfClose)
  }

  def handleCallError(
      implicit F: ConcurrentEffect[F]): (ClientCall.Listener[Response], ExitCase[Throwable]) => F[Unit] = {
    case (_, ExitCase.Completed) => F.unit
    case (_, ExitCase.Canceled)  => cancel("call was cancelled".some, None)
    case (_, ExitCase.Error(t))  => cancel(t.getMessage.some, t.some)
  }

  def unaryToUnaryCall(message: Request, headers: Metadata)(implicit F: ConcurrentEffect[F]): F[Response] = {
    F.bracketCase(startListener(Fs2UnaryClientCallListener[F, Response], headers))({ listener =>
      sendSingleMessage(message) *> listener.getValue
    })(handleCallError)
  }

  def streamingToUnaryCall(messages: Stream[F, Request], headers: Metadata)(
      implicit F: ConcurrentEffect[F]): F[Response] = {
    F.bracketCase(startListener(Fs2UnaryClientCallListener[F, Response], headers))({ listener =>
      Stream.eval(listener.getValue).concurrently(sendStream(messages)).compile.lastOrError
    })(handleCallError)
  }

  def unaryToStreamingCall(message: Request, headers: Metadata)(
      implicit F: ConcurrentEffect[F]): Stream[F, Response] = {
    Stream
      .bracketCase(startListener(Fs2StreamClientCallListener[F, Response](call.request), headers))(handleCallError)
      .flatMap(Stream.eval_(sendSingleMessage(message)) ++ _.stream)
  }

  def streamingToStreamingCall(messages: Stream[F, Request], headers: Metadata)(
      implicit F: ConcurrentEffect[F]): Stream[F, Response] = {
    Stream
      .bracketCase(startListener(Fs2StreamClientCallListener[F, Response](call.request), headers))(handleCallError)
      .flatMap(_.stream.concurrently(sendStream(messages)))
  }
}

object Fs2ClientCall {

  class PartiallyAppliedClientCall[F[_]](val dummy: Boolean = false) extends AnyVal {

    def apply[Request, Response](
        channel: Channel,
        methodDescriptor: MethodDescriptor[Request, Response],
        callOptions: CallOptions)(implicit F: Sync[F]): F[Fs2ClientCall[F, Request, Response]] =
      apply(channel.newCall[Request, Response](methodDescriptor, callOptions))

    def apply[Request, Response](call: ClientCall[Request, Response])(
        implicit F: Sync[F]): F[Fs2ClientCall[F, Request, Response]] =
      for {
        wakeOnReady <- Ref[F].of(none[Deferred[F, Unit]])
      } yield new Fs2ClientCall(call, wakeOnReady)
  }

  def apply[F[_]]: PartiallyAppliedClientCall[F] =
    new PartiallyAppliedClientCall[F]
}
