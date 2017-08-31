package de.beuth.linkedin.scanner.api

import akka.{Done}
import de.beuth.scanner.commons._
import com.lightbend.lagom.scaladsl.api.transport.Method
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}

import scala.collection.immutable.Seq


/**
  * This Service scrapes linkedin profiles and publishes the updates to the message broker
  * as well as the status of the scan
  *
  * provides one endpoint and 2 topics
  */
object LinkedinScannerService {
  val NAME = "linkedin"
  val TOPIC_STATUS = s"${NAME}Status"
  val TOPIC_UPDATE = s"${NAME}Update"
}

trait LinkedinScannerService extends Service with ScanStatusTopic with ProfileUpdateTopic {

  /**
    * Scrapes data from a single LinkedinProfile with given url
    *
    * @param keyword related keyword
    * @serviceCallBody url of profile to scrape
    * @serviceCallReturn Done when sucessfully invoked (may not completes operation)
    */
  def scrape(keyword: String): ServiceCall[String, Done]

  override final def descriptor = {
    import Service._
    named(s"${LinkedinScannerService.NAME}-scanner").withCalls(
      restCall(Method.POST, "/api/scanner/linkedin/:keyword", scrape _)
    ).withTopics(
      topic(LinkedinScannerService.TOPIC_STATUS, statusTopic),
      topic(LinkedinScannerService.TOPIC_UPDATE, updateTopic)
    ).withAutoAcl(true)
  }
}
