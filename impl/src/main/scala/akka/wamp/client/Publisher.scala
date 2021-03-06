package akka.wamp.client

import akka.Done
import akka.actor.Actor._
import akka.actor.Cancellable
import akka.wamp._
import akka.wamp.messages._
import akka.wamp.serialization._

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent._

/**
  * Publisher is a client which publishes events to topics
  * without knowledge of subscribers
  */
trait Publisher { this: Session =>

  /** The Akka ActorSystem scheduler */
  val scheduler = system.scheduler
  
  /** The event publications map */
  private val pendingPublications: mutable.Map[PublicationId, Promise[Either[Done, Publication]]] = mutable.Map()

  /**
    * Publish to a topic
    *
    * @param topic is the topic to publish to
    * @return (a future of) either done or publication 
    */
  def publish(topic: Uri): Future[Either[Done, Publication]] =  {
    publish(topic, ack=false, Payload())
  }
  
  /**
    * Publish to a topic
    *
    * @param topic is the topic to publish to
    * @param ack is the acknowledge boolean switch
    * @return (a future of) either done or publication 
    */
  def publish(topic: Uri, ack: Boolean): Future[Either[Done, Publication]] =  {
    publish(topic, ack, Payload())
  }
  
  /**
    * Publish to a topic
    *
    * @param topic is the topic to publish to
    * @param data is the data list of arbitrary types to supply 
    * @param ack is the acknowledge boolean switch
    * @return (a future of) either done or publication 
    */
  def publish(topic: Uri, ack: Boolean, data: List[Any]): Future[Either[Done, Publication]] =  {
    publish(topic, ack, Payload(data))    
  }

  
  /**
    * Publish to a topic
    *
    * @param topic is the topic to publish to
    * @param kwdata is the data list of arbitrary types to supply 
    * @param ack is the acknowledge boolean switch
    * @return (a future of) either done or publication 
    */
  def publish(topic: Uri, ack: Boolean, kwdata: Map[String, Any]): Future[Either[Done, Publication]] =  {
    publish(topic, ack, Payload(kwdata))
  }


  /**
    * Publish to a topic
    *
    * @param topic is the topic to publish to
    * @param data is the data list of arbitrary types to supply
    * @param kwdata is the data list of arbitrary types to supply
    * @param ack is the acknowledge boolean switch
    * @return (a future of) either done or publication
    */
  def publish(topic: Uri, ack: Boolean, data: List[Any], kwdata: Map[String, Any]): Future[Either[Done, Publication]] =  {
    publish(topic, ack, Payload(data, kwdata))
  }

  /**
    * Publish a tick count to a topic with an initial delay and
    * frequency. 
    * 
    * E.g. if you would like a message to be sent immediately and
    * thereafter every 500ms you would set delay=Duration.Zero and
    * interval=Duration(500, TimeUnit.MILLISECONDS)
    * 
    * @param topic is the topic to publish the tick count to
    * @param delay is the initial delay 
    * @param interval is the interval between two ticks
    * @return
    */
  def tickle(topic: Uri, interval: FiniteDuration, delay: FiniteDuration = Duration.Zero): Future[Cancellable] = {
    var count = 0
    Future.successful(this.system.scheduler.schedule(delay, interval, new Runnable {
      override def run(): Unit = {
        count = count + 1
        publish(topic, ack = false, List(count))
      }
    }))
  }
  
  
  /** Publish to a topic */
  private def publish(topic: Uri, ack: Boolean, payload: Payload): Future[Either[Done, Publication]] =  {
    withPromise[Either[Done, Publication]] { promise =>
      val message = Publish(requestId = nextRequestId(), Dict().setAck(ack), topic, payload)
      pendingPublications += (message.requestId -> promise)
      if (!ack) {
        connection ! message
        promise.success(Left(Done))
      }
      else {
        connection ! message
      }
    }
  }
  

  protected def handlePublications: Receive = {
    case msg @ Published(requestId, _) =>
      log.debug("<-- {}", msg)
      pendingPublications.get(requestId).map { promise =>
        pendingPublications -= requestId
        promise.success(Right(new Publication(msg)))
      }
      
    case msg @ Error(Publish.tpe, requestId, _, error, _) =>
      log.debug("<-- {}", msg)
      pendingPublications.get(requestId).map { promise =>
        pendingPublications -= requestId  
        promise.failure(new SessionException(error))
      }
  }
}

object Publisher

/**
  * A publication
  * 
  * @param published 
  */
class Publication private[client] (val published: Published)