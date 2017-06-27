package de.beuth.ixquick.scanner.api


import java.time.Instant

import akka.{Done, NotUsed}
import de.beuth.scanner.commons._
import com.lightbend.lagom.scaladsl.api.Service.pathCall
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
import play.api.libs.json._
/**
  * Created by David on 13.06.17.
  */

object IxquickScannerService {
  val TOPIC_STATUS = "ixquick_status"
  val TOPIC_UPDATE = "ixquick_update"
}

trait IxquickScannerService extends Service with ScanStatusTopics {
//  extends ScanServiceWithDefaultTopics {

  def scanLinkedin(keyword: String): ServiceCall[NotUsed, Done]
  def scanXing(keyword: String): ServiceCall[NotUsed, Done]

  override final def descriptor = {
    import Service._

    named("ixquick-scanner").withCalls(
      pathCall("/api/scanner/ixquick/linkedin/:keyword", scanLinkedin _),
      pathCall("/api/scanner/ixquick/xing/:keyword", scanXing _)
    ).withTopics(
      topic(IxquickScannerService.TOPIC_STATUS, statusTopic),
      topic(IxquickScannerService.TOPIC_UPDATE, updateTopic)
    ).withAutoAcl(true)
  }

  def updateTopic(): Topic[IxquickScanUpdateEvent]
}

case class LinkedInUpdateEvent(keyword: String, data: Seq[String]) extends IxquickScanUpdateEvent
object LinkedInUpdateEvent {
  implicit val format: Format[LinkedInUpdateEvent] = Json.format
}

case class XingUpdateEvent(keyword: String, data: Seq[String]) extends IxquickScanUpdateEvent
object XingUpdateEvent {
  implicit val format: Format[XingUpdateEvent] = Json.format
}

sealed trait IxquickScanUpdateEvent {
  def keyword: String
  def data: Seq[String]
}

object IxquickScanUpdateEvent {
  implicit val reads: Reads[IxquickScanUpdateEvent] = {
    (__ \ "event_type").read[String].flatMap {
      case "linkedinUpdated" => implicitly[Reads[LinkedInUpdateEvent]].map(identity)
      case "xingUpdated" => implicitly[Reads[XingUpdateEvent]].map(identity)
      case other => Reads(_ => JsError(s"Unknown event type $other"))
    }
  }
  implicit val writes: Writes[IxquickScanUpdateEvent] = Writes { event =>
    val (jsValue, eventType) = event match {
      case m: LinkedInUpdateEvent => (Json.toJson(m)(LinkedInUpdateEvent.format), "linkedinUpdated")
      case m: XingUpdateEvent => (Json.toJson(m)(XingUpdateEvent.format), "xingUpdated")
    }
    jsValue.transform(__.json.update((__ \ 'event_type).json.put(JsString(eventType)))).get
  }
}
