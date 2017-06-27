package de.beuth.scanner.commons

import java.time.Instant

import com.lightbend.lagom.scaladsl.api.broker.Topic
import play.api.libs.json._

/**
  * Created by David on 25.06.17.
  */
trait ScanStatusTopics {
  def statusTopic(): Topic[ScanStatusEvent]
}

case class ScanStartedEvent(keyword: String, timestamp: Instant)  extends ScanStatusEvent
object ScanStartedEvent {
  implicit val format: Format[ScanStartedEvent] = Json.format
}

case class ScanFinishedEvent(keyword: String, timestamp: Instant)  extends ScanStatusEvent
object ScanFinishedEvent {
  implicit val format: Format[ScanFinishedEvent] = Json.format
}

case class ScanFailedEvent(keyword: String, timestamp: Instant, errorMsg: String) extends ScanStatusEvent
object ScanFailedEvent {
  implicit val format: Format[ScanFailedEvent] = Json.format
}

sealed trait ScanStatusEvent {
  def keyword: String
  def timestamp: Instant
}

object ScanStatusEvent {
  implicit val reads: Reads[ScanStatusEvent] = {
    (__ \ "event_type").read[String].flatMap {
      case "scanStarted" => implicitly[Reads[ScanStartedEvent]].map(identity)
      case "scanFinished" => implicitly[Reads[ScanFinishedEvent]].map(identity)
      case "scanFailed" => implicitly[Reads[ScanFailedEvent]].map(identity)
      case other => Reads(_ => JsError(s"Unknown event type $other"))
    }
  }
  implicit val writes: Writes[ScanStatusEvent] = Writes { event =>
    val (jsValue, eventType) = event match {
      case m: ScanStartedEvent => (Json.toJson(m)(ScanStartedEvent.format), "scanStarted")
      case m: ScanFinishedEvent => (Json.toJson(m)(ScanFinishedEvent.format), "scanFinished")
      case m: ScanFailedEvent => (Json.toJson(m)(ScanFailedEvent.format), "scanFailed")
    }
    jsValue.transform(__.json.update((__ \ 'event_type).json.put(JsString(eventType)))).get
  }
}
