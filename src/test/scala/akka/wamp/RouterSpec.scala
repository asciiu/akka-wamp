package akka.wamp

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import akka.wamp.Router.ProtocolError
import akka.wamp.messages._
import org.scalatest._

class RouterSpec extends TestKit(ActorSystem()) with ImplicitSender with WordSpecLike with MustMatchers {
  "The router" when {
    "handling sessions" should {

      "open a new session if client says HELLO" in new Fixture {
        routerRef ! Hello("akka.wamp.realm", Dict.withRoles("publisher"))
        expectMsg(Welcome(0L, Dict.withRoles("broker")))
        router.realms must have size(1)
        router.realms must contain only ("akka.wamp.realm")
        router.sessions must have size(1)
        val sid = router.sessions.keySet.head
        val session = router.sessions(sid)
        session must have (
          'id (sid),
          'peer1 (routerRef),
          'peer2 (testActor),
          'realm ("akka.wamp.realm")
        )
      }

      "abort if client says HELLO for unknown realm" in new Fixture {
        routerRef ! Hello("unknown.realm", Dict.withRoles("any"))
        expectMsg(Abort(Dict.withMessage("The realm unknown.realm does not exist."), "wamp.error.no_such_realm"))
        router.realms must have size(1)
        router.realms must contain only ("akka.wamp.realm")
        router.sessions mustBe empty
      }
      
      
      "auto-create realm if client says HELLO for unknown realm" in {
        pending
      }
      

      "protocol error if client says HELLO twice whatever realm" in new Fixture {
        routerRef ! Hello("akka.wamp.realm", Dict.withRoles("publisher"))
        expectMsgType[Welcome]
        routerRef ! Hello("whatever.realm", Dict.withRoles("whatever.role"))
        expectMsg(ProtocolError("Session already open"))
        router.sessions must have size(0)
      }


      // TODO WAMP specs don't clarify if client can open a second connection attached to a different realm?
    }
    
    "handling subscriptions" should {
      
      "???" in {
        pending
      }
    }
   }
  
  trait Fixture {
    def fakegen = (m: Map[Long, _]) => 0L 
    val routerRef = TestActorRef(Router.props(fakegen))
    val router = routerRef.underlyingActor.asInstanceOf[Router]
  }
}