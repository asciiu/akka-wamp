package akka.wamp.client

import akka.wamp.messages.Invocation
import akka.wamp.serialization.Payload
import org.scalamock.scalatest.MockFactory

import scala.concurrent._
import scala.concurrent.duration._

class CalleeSpec extends ClientBaseSpec with MockFactory {


  "A callee" should "fail register procedure when session closed" in { f =>
    f.withSession { session =>
      whenReady(session.close()) { _ =>
        val handler = stubFunction[Invocation, Future[Payload]]
        val registration = session.register("myapp.topic", handler)
        whenReady(registration.failed) { ex =>
          ex mustBe a[SessionException]
          ex.getMessage mustBe "session closed"
        }
      }
    }
  }

  it should "succeed register procedure with scala.Function and expect invocations" in { f =>
    f.withSession { session1 =>
      val handler = (a: Int, b: Int) => a + b
      val registration = session1.register("myapp.procedure.sum", handler)
      whenReady(registration) { registration =>
        registration.procedure mustBe "myapp.procedure.sum"
        registration.registered.requestId mustBe 1
        registration.registered.registrationId mustBe 1
      }
      // the caller could be another transport actor
      f.withTransport { transport2 =>
        whenReady(transport2.openSession()) { session2 =>
          val result = session2.call("myapp.procedure.sum", List(5, 8))
          whenReady(result) { result =>
            whenReady(result.data) { data =>
              data mustBe List(13)
            }
          }
        }
      }
    }
  }

  it should "succeed register procedure with InvocationHandler and expect invocations" in { f =>
    f.withSession { session1 =>
      val handler = stubFunction[Invocation, Future[Payload]]
      val payload = Future.successful(Payload(/*List("paolo", 40, true)*/))
      handler.when(*).returns(payload)
      val registration = session1.register("myapp.procedure", handler)
      whenReady(registration) { registration =>
        registration.procedure mustBe "myapp.procedure"
        registration.registered.requestId mustBe 1
        registration.registered.registrationId mustBe 1

        // the caller could be another transport actor
        f.withTransport { transport2 =>
          whenReady(transport2.openSession()) { session2 =>
            val result = session2.call("myapp.procedure")
            whenReady(result) { _ =>
              awaitAssert(handler.verify(*).once().returning(payload), 32 seconds)
            }
          }
        }
      }
    }
  }


  it should "fail unregister procedure when session closed" in { f =>
    f.withSession { session =>
      whenReady(session.close()) { _ =>
        val unregistered = session.unregister("myapp.procedure")
        whenReady(unregistered.failed) { ex =>
          ex mustBe a[SessionException]
          ex.getMessage mustBe "session closed"
        }
      }
    }
  }


  it should "succeed unregister procedure" in { f =>
    f.withSession { session =>
      val handler = stubFunction[Invocation, Future[Payload]]
      val registration = session.register("myapp.procedure", handler)
      whenReady(registration) { registration =>
        whenReady(registration.unregister()) { unregistered =>
          assert(true)
        }
      }
    }
  }
}
