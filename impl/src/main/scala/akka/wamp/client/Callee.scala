package akka.wamp.client

import akka.actor.Actor._
import akka.wamp._
import akka.wamp.messages._
import akka.wamp.serialization.Payload

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.{reflect => rf}
import scala.reflect.runtime.{universe => ru}


/**
  * A callee is a client that register procedures and
  * expects to receive invocations
  */
trait Callee { this: Session =>
  
  import Callee._
  
  private val pendingRegistrations: mutable.Map[RequestId, PendingRegistration] = mutable.Map()

  private val registrations: mutable.Map[RegistrationId, Registration] = mutable.Map()
  
  private val pendingUnregistrations: mutable.Map[RequestId, PendingUnregistration] = mutable.Map()

  
  /**
    * Register the given procedure URI so that the given invocation handler 
    * will be executed on INVOCATION from router.
    *
    * A callee announces the availability of an endpoint implementing a
    * procedure with a dealer by sending a REGISTER message:
    *
    * {{{
    *   ,------.          ,------.               ,------.
    *   |Caller|          |Dealer|               |Callee|
    *   `--+---'          `--+---'               `--+---'
    *      |                 |                      |
    *      |                 |                      |
    *      |                 |       REGISTER       |
    *      |                 | <---------------------
    *      |                 |                      |
    *      |                 |  REGISTERED or ERROR |
    *      |                 | --------------------->
    *      |                 |                      |
    *      |                 |                      |
    *      |                 |                      |
    *      |                 |                      |
    *      |                 |                      |
    *      |                 |      UNREGISTER      |
    *      |                 | <---------------------
    *      |                 |                      |
    *      |                 | UNREGISTERED or ERROR|
    *      |                 | --------------------->
    *   ,--+---.          ,--+---.               ,--+---.
    *   |Caller|          |Dealer|               |Callee|
    *   `------'          `------'               `------'
    * }}}
    *
    * @param procedure is the procedure URI the callee wants to register
    * @param handler is the handler executed on invocations
    * @return the (future of) registration
    */
  def register(procedure: Uri, handler: InvocationHandler): Future[Registration] = {
    withPromise[Registration] { promise =>
      val msg = Register(requestId = nextRequestId(), Register.defaultOptions, procedure)
      pendingRegistrations += (msg.requestId -> new PendingRegistration(msg, handler, promise))
      connection ! msg
    }
  }

  /**
    * Register the given procedure URI so that the given ``scala.Function``
    * implementation will be dinamically invoked (via the experimental Scala 2.11
    * reflection capabilities) on INVOCATION from router.
    *
    * @param procedure is the procedure URI the callee wants to register
    * @param handler is a partially applied function
    * @tparam T it must be any of the ``scala.Function`` types
    * @return the (future of) registration
    */
  def register[T](procedure: Uri, handler: T)(implicit tt: ru.TypeTag[T], ct: rf.ClassTag[T]): Future[Registration] = {
    register(procedure, { invocation: Invocation =>
      // TODO https://github.com/angiolep/akka-wamp/issues/41
      invocation.args.map { args =>
        val theType = ru.typeTag[T].tpe
        if (theType.typeSymbol.fullName.startsWith("scala.Function")) {
          val theMethod = theType.decl(ru.TermName("apply")).asMethod

          val mirror = ru.runtimeMirror(handler.getClass.getClassLoader)
          val instanceMirror = mirror.reflect(handler)
          val methodMirror = instanceMirror.reflectMethod(theMethod)

          val result = methodMirror.apply(args: _*)
          Payload(List(result))
        }
        else throw new SessionException("Either a scala.Fuction or an akka.wamp.InvocationHandler was expected")
      }
    })
  }
  

  private[client] def handleRegistrations: Receive = {
    case msg @ Registered(requestId, registrationId) =>
      log.debug("<-- {}", msg)
      pendingRegistrations.get(requestId).map { case pending =>
        val registration = new Registration(this, pending.msg.procedure, pending.handler, msg)
        registrations += (registrationId -> registration)
        pendingRegistrations -= requestId
        pending.promise.success(registration)
      }
      
    case msg @ Error(Register.tpe, requestId, _, error, _) =>
      log.debug("<-- {}", msg)
      pendingRegistrations.get(requestId).map { pending =>
        pendingRegistrations -= requestId
        pending.promise.failure(new SessionException(error))
      }
      
    case msg @ Unregistered(requestId) =>
      log.debug("<-- {}", msg)
      pendingUnregistrations.get(requestId).map { pending =>
          registrations -= pending.msg.registrationId
          pendingUnregistrations -= requestId
          pending.promise.success(msg)
      }

    case msg @ Error(Unregister.tpe, requestId, _, error, _) =>
      log.debug("<-- {}", msg)
      pendingUnregistrations.get(requestId).map { pending =>
          pendingUnregistrations -= requestId
          pending.promise.failure(new SessionException(error))
      }
  }
  
  
  // ~~~~~~~~~~~~~~~~~~~~~~~


  /**
    * Unregister from the given topic
    *
    * @param procedure is the procedure to unregister
    * @return a (future of) unregistered
    */
  def unregister(procedure: Uri): Future[Unregistered] = {
    withPromise[Unregistered] { promise =>
      registrations.find { case (_, registration) =>  registration.procedure == procedure } match {
        case Some((registrationId, _)) => {
          val msg = Unregister(requestId = nextRequestId(), registrationId)
          pendingUnregistrations += (msg.requestId -> new PendingUnregistration(msg, promise))
          connection ! msg
        }
        case None =>
          Future.failed[Unregistered](new SessionException("akka.wamp.error.no_such_procedure"))
      }
    }
  }


  // ~~~~~~~~~~~~~~~~~~~~~~~

  
  protected def handleInvocations: Receive = {
    case msg @ Invocation(requestId, registrationId, _, _) =>
      log.debug("<-- {}", msg)
      registrations.get(registrationId) match {
        case Some(registration) => 
          val payload = registration.handler.apply(msg)
          payload.map { p => 
            connection ! Yield(requestId, payload = p) 
          }
          
        case None => 
          log.warn("!!! invocation handler not found for registrationId {}", registrationId)
      }
  }
}


object Callee {
  
  private class PendingRegistration(val msg: Register, val handler: InvocationHandler, val promise: Promise[Registration])
  
  private class PendingUnregistration(val msg: Unregister, val promise: Promise[Unregistered])
}


/**
  * A registration
  * 
  * @param callee is the callee which have registered the procedure
  * @param procedure is the registered procedure URI
  * @param handler is the invocation handler
  * @param registered is the message replied back from the router
  */
class Registration private[client] (
  callee: Callee,
  val procedure: Uri, 
  val handler: InvocationHandler, 
  val registered: Registered) {

  /**
    * Unregister this registration
    * 
    * @return a (future of) unregistered message
    */
  def unregister(): Future[Unregistered] = callee.unregister(procedure)
}
