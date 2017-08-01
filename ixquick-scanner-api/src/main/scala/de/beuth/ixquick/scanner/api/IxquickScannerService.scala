package de.beuth.ixquick.scanner.api

import akka.{Done, NotUsed}
import de.beuth.scanner.commons._
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
import play.api.libs.json._
import scala.collection.immutable.Seq

/**
  * Created by David on 13.06.17.
  */

object IxquickScannerService {
  val NAME = "ixquick"
  val TOPIC_STATUS = s"${NAME}_status"
  val TOPIC_UPDATE = s"${NAME}_update"
}

trait IxquickScannerService extends Service with ScanStatusTopics {


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

case class IxquickScanUpdateEvent(keyword: String, data: Seq[String])
object IxquickScanUpdateEvent {
  implicit val format: Format[IxquickScanUpdateEvent] = Json.format
}

