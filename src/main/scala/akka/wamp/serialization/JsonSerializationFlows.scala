package akka.wamp.serialization

import akka.NotUsed
import akka.http.scaladsl.model.ws.{BinaryMessage, TextMessage, Message => WebSocketMessage}
import akka.stream.ActorAttributes.supervisionStrategy
import akka.stream.{Materializer, Supervision}
import akka.stream.scaladsl.Flow
import akka.wamp.messages.{Validator, Message => WampMessage}
import org.slf4j.LoggerFactory

class JsonSerializationFlows(validator: Validator, materializer: Materializer) extends SerializationFlows {
  val log = LoggerFactory.getLogger(classOf[SerializationFlows])
  val json = new JsonSerialization

  /**
    * Serialize from WampMessage object to textual WebSocketMessage
    */
  val serialize: Flow[WampMessage, WebSocketMessage, NotUsed] =
    Flow[WampMessage].
      map {
        case message: WampMessage =>
          val source = json.serialize(message)
          TextMessage(source)
      }



  /**
    * Deserialize textual WebSocketMessage to WampMessage object
    */
  val deserialize: Flow[WebSocketMessage, WampMessage, NotUsed] =
    Flow[WebSocketMessage]
      .map {
        case TextMessage.Strict(text) =>
          json.deserialize(text)(validator, materializer)

        case TextMessage.Streamed(source) =>
          throw new DeserializeException("Streaming not supported yet.")

        case m: BinaryMessage =>
          throw new DeserializeException("Cannot deserialize binary message as JSON message was expected instead!")
      }
      .withAttributes(supervisionStrategy {
        case ex: DeserializeException =>
          log.warn("DeserializeException: {}", ex.getMessage)
          Supervision.Resume
      })
}