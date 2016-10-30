package akka.wamp.serialization

import akka.NotUsed
import akka.http.scaladsl.model.{ws => websocket}
import akka.http.scaladsl.model.ws.{BinaryMessage, TextMessage}
import akka.stream.ActorAttributes.supervisionStrategy
import akka.stream.{Materializer, Supervision}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.wamp.Validator
import akka.wamp.{messages => wamp}
import org.slf4j.LoggerFactory

import scala.concurrent.Future

/**
  * The JSON serialization flows
  * 
  * @param validateStrictUri is the boolean switch (default is false) to validate against strict URIs rather than loose URIs
  * @param dropOffendingMessages is the boolean switch to drop an offending message and resume to the next one 
  * @param mat is the Akka Stream materializer
  */
class JsonSerializationFlows(validateStrictUri: Boolean, dropOffendingMessages: Boolean)
                            (implicit mat: Materializer) 
  extends SerializationFlows 
{
  val log = LoggerFactory.getLogger(classOf[SerializationFlows])
  
  val serialization = new JsonSerialization()

  /** The WAMP types validator */
  implicit val validator = new Validator(validateStrictUri)
  
  /** The actor system dispatcher */
  implicit val ec = mat.executionContext


  /**
    * Serialize from wamp.Message object to textual websocket.Message
    */
  val serialize: Flow[wamp.Message, websocket.Message, NotUsed] =
    Flow[wamp.Message].
      mapAsync(1) {
        case message: wamp.Message =>
          val textStream = serialization.serialize(message)
          textStream.runReduce(_ + _).map(txt => TextMessage(txt))
      }



  /**
    * Deserialize textual websocket.Message to wamp.Message object
    */
  val deserialize: Flow[websocket.Message, wamp.Message, NotUsed] =
    Flow[websocket.Message]
      .mapAsync(1) {
        case TextMessage.Strict(text) =>
          Future.successful(serialization.deserialize(Source.single(text)))

        case TextMessage.Streamed(source) =>
          source.runReduce(_ + _).map ( str => serialization.deserialize(Source.single(str)))
          //serialization.deserialize(source)

        case bm: BinaryMessage =>
          // ignore binary messages but drain content to avoid the stream being clogged
          bm.dataStream.runWith(Sink.ignore)
          throw new DeserializeException("Cannot deserialize binary message as JSON message was expected instead!")
      }
      .withAttributes(supervisionStrategy {
        case ex: DeserializeException =>
          if (!dropOffendingMessages) {
            // default
            log.error(ex.getMessage, ex)
            Supervision.Stop
          }
          else {
            log.warn("Resume from DeserializeException: {}", ex.getMessage)
            Supervision.Resume
          }
      })
}