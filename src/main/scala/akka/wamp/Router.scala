package akka.wamp

import akka.actor.{ActorRef, Props}
import akka.wamp.messages._


/**
  * A Router is a [[Peer]] of the roles [[Broker]] and [[Dealer]] which is responsible 
  * for generic call and event routing and do not run any application code.
  * 
  * @param idgen is the session identifiers generator
  */
class Router (idgen: IdGenerator) extends Peer /* TODO with Broker*/ /* TODO with Dealer */ {
  import Router._

  /**
    * Map of existing realms
    */
  var realms = Set[Uri]("akka.wamp.realm")
  
  /**
    * Map of open [[Session]]s by their ids
    */
  var sessions = Map.empty[Long, Session]
  
  def receive = handleSessions /* TODO orElse handleSubscriptions*/ /* TODO orElse handleProcedures */

  /**
    * Handle session lifecycle related messages such as: HELLO, WELCOME, ABORT and GOODBYE
    */
  def handleSessions: Receive = {
    
    case Hello(realm, details) =>
      switchOn(sender())(
        
        (peer2,  session) => {
          /*
           * It is a protocol error to receive a second "HELLO" message 
           * during the lifetime of the session and the peer must fail 
           * the session if that happens.
           */
          closeSession(session.id)
          peer2 ! ProtocolError("Session already open")
        },
        
        (peer2) => {
          if (!realms.contains(realm)) {
            /*
             * The behavior if a requested "Realm" does not presently 
             * exist is router-specific.  A router may e.g. automatically create
             * the realm, or deny the establishment of the session with a "ABORT"
             * reply message. 
             */
            if (!autoCreateRealms) {
              peer2 ! Abort(DictBuilder().withEntry("message", s"The realm $realm does not exist.").build(), "wamp.error.no_such_realm")
            }
            else {
              createRealm(realm)
              openSession(peer2, realm)
            }
          }
          else {
            openSession(peer2, realm)
          }
        }
      )

      
    // ignore ABORT message from client
    case Abort => ()  

      
    case Goodbye(details, reason) =>
      switchOn(sender())(
        (peer2,  session) => {
          closeSession(session.id)
          peer2 ! Goodbye(DictBuilder().build(), "wamp.error.goodbye_and_out")
        },
        (peer2) => {
          peer2 ! ProtocolError("No session was open")
        }
      )
      
  }

  
  private def switchOn(peer2: ActorRef)(f1: (ActorRef, Session) => Unit, f2: (ActorRef) => Unit): Unit = {
    sessions.values.find(_.peer2 == peer2) match {
      case Some(session) => f1(peer2, session)
      case None => f2(peer2)
    }
  }
  
  private def openSession(peer2: ActorRef, realm: Uri): Unit = {
    val id = idgen(sessions)
    sessions += (id -> new Session(id, self, peer2, realm))
    // TODO log.debug("New session open ...")
    // TODO how to read the artifact version from build.sbt?
    peer2 ! Welcome(id, DictBuilder().withEntry("agent", "akka-wamp-0.1.0").withRoles("broker").build())
  }
  
  private def closeSession(id: Long) = {
    sessions -= id
  }
  
  private def createRealm(realm: Uri) = {
    realms += realm
  }
  
  private val autoCreateRealms = context.system.settings.config.getBoolean("akka.wamp.auto-create-realms")
}



object Router {
  /**
    * Create a Props for an actor of this type
    *
    * @param idgen is the identifier generator
    * @return the props
    */
  def props(idgen: IdGenerator = Session.randomIdNotIn()) = Props(new Router(idgen))
  
  /**
    * Sent when protocol errors occur
    * 
    * @param message
    */
  case class ProtocolError(message: String) extends Signal
  
}


