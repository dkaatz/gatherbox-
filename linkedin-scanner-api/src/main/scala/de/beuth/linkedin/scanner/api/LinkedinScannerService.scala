package de.beuth.linkedin.scanner.api

import akka.{Done, NotUsed}
import de.beuth.scanner.commons._
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
import play.api.libs.json._
import scala.collection.immutable.Seq

/**
  * Created by David on 13.06.17.
  */

object LinkedinScannerService {
  val NAME = "linkedin"
  val TOPIC_STATUS = s"${NAME}Status"
  val TOPIC_UPDATE = s"${NAME}Update"
}

trait LinkedinScannerService extends Service with ScanStatusTopics {


  /**
    * Performs a full scan of for linkedin profiles for the given keyword. This includes:
    *
    *  - collect(search) all profiles
    *  - scrape each profile and collect the data
    *
    * This endpoint invokes [[LinkedinScannerService.collect()]] and for each collected profile
    *  [[LinkedinScannerService.scrape()]]
    *
    * @param keyword Keyword to scan
    * @return Done when sucessfully invoked (may not completes operation)
    */
  def scan(keyword: String): ServiceCall[NotUsed, Done]

  /**
    * Searches on Ixquick for profiles and collects the urls
    *
    * @param keyword Keyword to scan
    * @return Done when sucessfully invoked (may not completes operation)
    */
  def collect(keyword: String): ServiceCall[NotUsed, Done]

  /**
    * Scrapes data from a single LinkedinProfile with given url
    *
    * @param keyword Keyword to scan
    *
    * @return Done when sucessfully invoked (may not completes operation)
    */
  def scrape(keyword: String): ServiceCall[String, Done]

  /**
    * Retruns the current state for a keyword
    *
    * @param keyword
    */
  def get(keyword: String): ServiceCall[Profile, Done]

  override final def descriptor = {
    import Service._

    named("ixquick-scanner").withCalls(
      pathCall("/api/scanner/linkedin/:keyword/scan", scan _),
      pathCall("/api/scanner/linkedin/:keyword/collect", collect _),
      pathCall("/api/scanner/linkedin/:keyword/collect", collect _),
      pathCall("/api/scanner/linkedin/:keyword", get _)
    ).withTopics(
      topic(LinkedinScannerService.TOPIC_STATUS, statusTopic),
      topic(LinkedinScannerService.TOPIC_UPDATE, updateTopic)
    ).withAutoAcl(true)
  }

  def updateTopic(): Topic[IxquickScanUpdateEvent]
}

case class IxquickScanUpdateEvent(keyword: String, data: Seq[String])
object IxquickScanUpdateEvent {
  implicit val format: Format[IxquickScanUpdateEvent] = Json.format
}

