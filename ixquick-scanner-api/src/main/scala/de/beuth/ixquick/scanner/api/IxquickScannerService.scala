package de.beuth.ixquick.scanner.api

import akka.{Done, NotUsed}
import de.beuth.scanner.commons._
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
import play.api.libs.json._
import scala.collection.immutable.Seq

/**
  * This service is responsible for scanning for profile links on ixquick
  *
  * it provides 2 endpoints and 2 topics
  */
object IxquickScannerService {
  val NAME = "ixquick"
  val TOPIC_STATUS = s"${NAME}Status"
  val TOPIC_UPDATE = s"${NAME}Update"
}

trait IxquickScannerService extends Service with ScanStatusTopic {

  /**
    * Scan for german and english linkedin profiles
    * @param keyword associated keyword
    */
  def scanLinkedin(keyword: String): ServiceCall[NotUsed, Done]
  /**
    * Scan for xing profiles
    * @param keyword associated keyword
    */
  def scanXing(keyword: String): ServiceCall[NotUsed, Done]

  override final def descriptor = {
    import Service._
    //name of the service
    named(s"${IxquickScannerService.NAME}-scanner").withCalls(
      //the two endpoints of the service
      pathCall("/api/scanner/ixquick/linkedin/:keyword", scanLinkedin _),
      pathCall("/api/scanner/ixquick/xing/:keyword", scanXing _)
    ).withTopics(
      //the two topics to publish
      topic(IxquickScannerService.TOPIC_STATUS, statusTopic),
      topic(IxquickScannerService.TOPIC_UPDATE, updateTopic)
    )
      .withAutoAcl(true) //specifies if api is public or not
  }

  //the update topic
  def updateTopic(): Topic[IxquickScanUpdateEvent]
}

/**
  * Message format for the update topic
  * @param keyword associated keyword
  * @param data the list of urls
  */
case class IxquickScanUpdateEvent(keyword: String, data: Seq[String])
object IxquickScanUpdateEvent {
  implicit val format: Format[IxquickScanUpdateEvent] = Json.format
}

