package org.lyranthe.fs2_grpc
package java_runtime
package client

import cats.effect.{ContextShift, IO, Timer}
import cats.effect.laws.util.TestContext
import fs2._
import io.grpc._
import minitest._

import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.Success

object ClientSuite extends SimpleTestSuite {

  test("single message to unaryToUnary") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)

    val dummy  = new DummyClientCall()
    val client = Fs2ClientCall[IO](dummy).unsafeRunSync()
    val result = client.unaryToUnaryCall("hello", new Metadata()).unsafeToFuture()
    dummy.listener.get.onMessage(5)

    // Check that call does not complete after result returns
    ec.tick()
    assertEquals(result.value, None)

    dummy.listener.get.onClose(Status.OK, new Metadata())

    // Check that call completes after status
    ec.tick()
    assertEquals(result.value, Some(Success(5)))
    assertEquals(dummy.messagesSent.size, 1)
    assertEquals(dummy.requested, 1)
  }

  test("cancellation for unaryToUnary") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)
    implicit val timer: Timer[IO]     = ec.timer

    val dummy  = new DummyClientCall()
    val client = Fs2ClientCall[IO](dummy).unsafeRunSync()
    val result = client.unaryToUnaryCall("hello", new Metadata()).timeout(1.second).unsafeToFuture()

    ec.tick()
    dummy.listener.get.onMessage(5)

    // Check that call does not complete after result returns
    ec.tick()
    assertEquals(result.value, None)

    // Check that call is cancelled after 1 second
    ec.tick(2.seconds)

    assert(result.value.get.isFailure)
    assert(result.value.get.failed.get.isInstanceOf[TimeoutException])
    assertEquals(dummy.cancelled.isDefined, true)
  }

  test("no response message to unaryToUnary") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)

    val dummy  = new DummyClientCall()
    val client = Fs2ClientCall[IO](dummy).unsafeRunSync()
    val result = client.unaryToUnaryCall("hello", new Metadata()).unsafeToFuture()

    dummy.listener.get.onClose(Status.OK, new Metadata())

    // Check that call completes after status but no message
    ec.tick()
    assert(result.value.isDefined)
    assert(result.value.get.isFailure)
    assert(result.value.get.failed.get.isInstanceOf[StatusRuntimeException])
    assertEquals(dummy.messagesSent.size, 1)
    assertEquals(dummy.requested, 1)
  }

  test("error response to unaryToUnary") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)

    val dummy  = new DummyClientCall()
    val client = Fs2ClientCall[IO](dummy).unsafeRunSync()
    val result = client.unaryToUnaryCall("hello", new Metadata()).unsafeToFuture()
    dummy.listener.get.onMessage(5)

    dummy.listener.get.onClose(Status.INTERNAL, new Metadata())

    // Check that call completes after status but no message
    ec.tick()
    assert(result.value.isDefined)
    assert(result.value.get.isFailure)
    assert(result.value.get.failed.get.isInstanceOf[StatusRuntimeException])
    assertEquals(result.value.get.failed.get
                   .asInstanceOf[StatusRuntimeException]
                   .getStatus,
                 Status.INTERNAL)
    assertEquals(dummy.messagesSent.size, 1)
    assertEquals(dummy.requested, 1)
  }

  test("stream to streamingToUnary") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)

    val dummy  = new DummyClientCall()
    val client = Fs2ClientCall[IO](dummy).unsafeRunSync()
    val result = client
      .streamingToUnaryCall(Stream.emits(List("a", "b", "c")), new Metadata())
      .unsafeToFuture()

    dummy.listener.get.onMessage(5)

    // Check that call does not complete after result returns
    ec.tick()
    assertEquals(result.value, None)

    dummy.listener.get.onClose(Status.OK, new Metadata())

    // Check that call completes after status
    ec.tick()
    assertEquals(result.value, Some(Success(5)))
    assertEquals(dummy.messagesSent.size, 3)
    assertEquals(dummy.requested, 1)
  }

  test("0-length to streamingToUnary") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)

    val dummy  = new DummyClientCall()
    val client = Fs2ClientCall[IO](dummy).unsafeRunSync()
    val result = client
      .streamingToUnaryCall(Stream.empty, new Metadata())
      .unsafeToFuture()

    dummy.listener.get.onMessage(5)

    // Check that call does not complete after result returns
    ec.tick()
    assertEquals(result.value, None)

    dummy.listener.get.onClose(Status.OK, new Metadata())

    // Check that call completes after status
    ec.tick()
    assertEquals(result.value, Some(Success(5)))
    assertEquals(dummy.messagesSent.size, 0)
    assertEquals(dummy.requested, 1)
  }

  test("single message to unaryToStreaming") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)

    val dummy  = new DummyClientCall()
    val client = Fs2ClientCall[IO](dummy).unsafeRunSync()
    val result = client.unaryToStreamingCall("hello", new Metadata()).compile.toList.unsafeToFuture()

    dummy.listener.get.onMessage(1)
    dummy.listener.get.onMessage(2)
    dummy.listener.get.onMessage(3)

    // Check that call does not complete after result returns
    ec.tick()
    assertEquals(result.value, None)

    dummy.listener.get.onClose(Status.OK, new Metadata())

    // Check that call completes after status
    ec.tick()
    assertEquals(result.value, Some(Success(List(1, 2, 3))))
    assertEquals(dummy.messagesSent.size, 1)
    assertEquals(dummy.requested, 4)
  }

  test("stream to streamingToStreaming") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)

    val dummy  = new DummyClientCall()
    val client = Fs2ClientCall[IO](dummy).unsafeRunSync()
    val result =
      client
        .streamingToStreamingCall(Stream.emits(List("a", "b", "c", "d", "e")), new Metadata())
        .compile
        .toList
        .unsafeToFuture()
    dummy.listener.get.onMessage(1)
    dummy.listener.get.onMessage(2)
    dummy.listener.get.onMessage(3)

    // Check that call does not complete after result returns
    ec.tick()
    assertEquals(result.value, None)

    dummy.listener.get.onClose(Status.OK, new Metadata())

    // Check that call completes after status
    ec.tick()
    assertEquals(result.value, Some(Success(List(1, 2, 3))))
    assertEquals(dummy.messagesSent.size, 5)
    assertEquals(dummy.requested, 4)
  }

  test("cancellation for streamingToStreaming") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)
    implicit val timer: Timer[IO]     = ec.timer

    val dummy  = new DummyClientCall()
    val client = Fs2ClientCall[IO](dummy).unsafeRunSync()
    val result =
      client
        .streamingToStreamingCall(Stream.emits(List("a", "b", "c", "d", "e")), new Metadata())
        .compile
        .toList
        .timeout(1.second)
        .unsafeToFuture()
    ec.tick()
    dummy.listener.get.onMessage(1)
    dummy.listener.get.onMessage(2)
    dummy.listener.get.onMessage(3)

    // Check that call does not complete after result returns
    ec.tick()
    assertEquals(result.value, None)

    // Check that call completes after status
    ec.tick(2.seconds)
    assert(result.value.get.isFailure)
    assert(result.value.get.failed.get.isInstanceOf[TimeoutException])
    assertEquals(dummy.cancelled.isDefined, true)
  }

  test("error returned from streamingToStreaming") {

    implicit val ec: TestContext      = TestContext()
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)

    val dummy  = new DummyClientCall()
    val client = Fs2ClientCall[IO](dummy).unsafeRunSync()
    val result =
      client
        .streamingToStreamingCall(Stream.emits(List("a", "b", "c", "d", "e")), new Metadata())
        .compile
        .toList
        .unsafeToFuture()

    dummy.listener.get.onMessage(1)
    dummy.listener.get.onMessage(2)
    dummy.listener.get.onMessage(3)

    // Check that call does not complete after result returns
    ec.tick()
    assertEquals(result.value, None)

    dummy.listener.get.onClose(Status.INTERNAL, new Metadata())

    // Check that call completes after status
    ec.tick()
    assert(result.value.isDefined)
    assert(result.value.get.isFailure)
    assert(result.value.get.failed.get.isInstanceOf[StatusRuntimeException])
    assertEquals(result.value.get.failed.get
                   .asInstanceOf[StatusRuntimeException]
                   .getStatus,
                 Status.INTERNAL)
    assertEquals(dummy.messagesSent.size, 5)
    assertEquals(dummy.requested, 4)
  }

  test("resource awaits termination of managed channel") {
    implicit val ec: TestContext = TestContext()

    import implicits._
    val result = ManagedChannelBuilder.forAddress("127.0.0.1", 0).resource[IO].use(IO.pure).unsafeToFuture()

    ec.tick()

    val channel = result.value.get.get
    assert(channel.isTerminated)
  }
}
