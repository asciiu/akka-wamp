package akka.wamp.router

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.{Message => WebSocketMessage}
import akka.http.scaladsl.server._
import akka.stream.scaladsl._

/**
  * It tests the ``router.Connection`` behaviour with its 
  * 
  *   DEFAULT configuration
  * 
  * (NO custom settings) by exercising it with various JSON messages covering
  * the most common scenarios (session handling, subscriptions, publications, etc.)
  */
class ConnectionSpec extends ConnectionFixtureSpec
{
  "The default router.Connection" should "reject websocket requests if no subprotocol matches" in { f =>
    WS(URL, clientSideHandler = Flow[WebSocketMessage]) ~> f.httpRoute ~> check {
      rejections.collect {
        case UnsupportedWebSocketSubprotocolRejection(p) => p
      }.toSet mustBe Set("wamp.2.json")
    }
    WS(URL, clientSideHandler = Flow[WebSocketMessage], List("other")) ~> Route.seal(f.httpRoute) ~> check {
      status mustBe StatusCodes.BadRequest
      responseAs[String] mustBe "None of the websocket subprotocols offered in the request are supported. Supported are 'wamp.2.json'."
      header("Sec-WebSocket-Protocol").get.value() mustBe "wamp.2.json"
    }
  }


  
  it should "reject any non-websocket requests" in { fixture =>
    Get(URL) ~> fixture.httpRoute ~> check {
      rejection mustBe ExpectedWebSocketRequestRejection
    }
    Get(URL) ~> Route.seal(fixture.httpRoute) ~> check {
      status mustBe StatusCodes.BadRequest
      responseAs[String] mustBe "Expected WebSocket Upgrade request"
    }
  }

  
  
  it should "drop offending messages and resume" in { f =>
    
    // TODO --> bad messages 
    
    // TODO  --> bad ABORT : no HELLO to reply to
    
    // --> bad GOODBYE : no session
    // --> drop
    f.client.sendMessage("""[6,{},"wamp.error.close_realm"]""")
    
    // --> bad SUBSCRIBE : no session
    // --> drop
    f.client.sendMessage("""[32,1,{},"myapp.TOPIC-"]""")

    // TODO  --> bad UNSUBSCRIBE : no session
    
    // --> bad PUBLISH : no session
    // --> drop
    f.client.sendMessage("""[16,2,{"acknowledge":true},"myapp.TOPIC-"]""")
    
    // --> bad REGISTER : no session
    // --> drop
    f.client.sendMessage("""[64,3,{},"myapp.procedure1"]""")

    // TODO  --> bad UNREGISTER : no session
    
    // TODO  --> bad YIELD : no session
    
    // --> bad CALL : no session
    // --> drop
    f.client.sendMessage("""[48,4,{},"myapp.procedure"]""")
    
    // --> bad HELLO : invalid realm URI
    //     dropped
    f.client.sendMessage("""[1,"invalid..realm",{"roles":{"subscriber":{}}}]""")
    
    // --> bad HELLO : invalid key
    //     dropped
    f.client.sendMessage("""[1,"myapp.realm",{"INVALID KEY":null}]""")
    
    // --> bad HELLO : invalid role
    //     dropped
    f.client.sendMessage("""[1,"myapp.realm",{"roles":{"invalid role":{}}}]""")

    // --> HELLO
    // <-- WELCOME
    f.client.sendMessage("""[1,"myapp.realm",{"roles":{"publisher":{}}}]""")
    f.client.expectMessage("""[2,1,{"agent":"akka-wamp-0.10.0","roles":{"broker":{},"dealer":{}}}]""")

    // SESSION #1 OPEN
    // \
      // --> bad HELLO : 2nd within session lifecycle
      // <-- ABORT
      f.client.sendMessage("""[1,"myapp.realm",{"roles":{"publisher":{}}}]""")
      f.client.expectMessage("""[3,{},"akka.wamp.error.session_already_open"]""")
    // \
    // SESSION #1 CLOSED
    
    // --> HELLO
    // <-- WELCOME
    f.client.sendMessage("""[1,"myapp.realm",{"roles":{"publisher":{}}}]""")
    f.client.expectMessage("""[2,2,{"agent":"akka-wamp-0.10.0","roles":{"broker":{},"dealer":{}}}]""")

    // SESSION #2 OPEN
    // \
      // --> bad GOODBYE : invalid reason URI
      //     dropped
      f.client.sendMessage("""[6,{},"invalid..reason"]""")
      // --> GOODBYE
      // <-- GOODBYE
      f.client.sendMessage("""[6,{},"wamp.error.close_realm"]""")
      f.client.expectMessage("""[6,{},"wamp.error.goodbye_and_out"]""")
    // \
    // SESSION #2 CLOSED

    // --> HELLO
    // <-- WELCOME
    f.client.sendMessage("""[1,"myapp.realm",{"roles":{"subscriber":{},"publisher":{},"callee":{}}}]""")
    f.client.expectMessage("""[2,3,{"agent":"akka-wamp-0.10.0","roles":{"broker":{},"dealer":{}}}]""")

    // SESSION #3 OPEN
    // \
      // --> bad SUBSCRIBE : invalid topic URI
      //     dropped
      f.client.sendMessage("""[32,1,{},"invalid..topic'"]""")
      
      // --> SUBSCRIBE
      // <-- SUBSCRIBED
      f.client.sendMessage("""[32,1,{},"myapp.TOPIC-"]""")
      f.client.expectMessage("""[33,1,1]""")
      
      // --> bad PUBLISH : invalid topic URI
      //     dropped
      f.client.sendMessage("""[16,2,{"acknowledge":true},"invalid..topic"]""")
      // --> PUBLISH
      // <-- PUBLISHED
      f.client.sendMessage("""[16,2,{"acknowledge":true},"myapp.TOPIC-"]""")
      f.client.expectMessage("""[17,2,4]""")

      // --> bad REGISTER : invalid procedure URI
      //     dropped
      f.client.sendMessage("""[64,3,{},"invalid..procedure"]""")
      // --> REGISTER
      // <-- REGISTERED
      f.client.sendMessage("""[64,3,{},"myapp.PROCEDURE-"]""")
      f.client.expectMessage("""[65,3,2]""")

      // --> bad CALL : invalid procedure URI
      //     dropped
//      f.client.sendMessage("""[48,4,{},"invalid..procedure"]""")
      // --> CALL
      // <-- RESULT
//      f.client.sendMessage("""[48,4,{},"myapp.PROCEDURE-"]""")
      // TODO f.client.expectMessage("""[50,4]""")
      
      // --> GOODBYE
      // <-- GOODBYE
      f.client.sendMessage("""[6,{},"wamp.error.close_realm"]""")
      f.client.expectMessage("""[6,{},"wamp.error.goodbye_and_out"]""")
    // \
    // SESSION #3 CLOSED

    f.client.expectNoMessage()
  }
}