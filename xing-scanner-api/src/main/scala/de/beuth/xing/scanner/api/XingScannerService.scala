package de.beuth.xing.scanner.api

import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.transport.Method
import com.lightbend.lagom.scaladsl.api.{Descriptor, Service, ServiceCall}
import de.beuth.scanner.commons.{ProfileScannerState, ProfileUpdateTopic, ScanStatusTopic}

/**
  * This Service scrapes Xing Profiles, storing and publishing the extracted Data
  *
  * This service provides 2 Topics:
  *   - a status topic to propagate the status of a scan for a keyword
  *   - a update topic to porpagate the results of a scraping
  *
  * and one endpoint:
  *   - a endpoint to start a profile scan for a specific password
  */
object XingScannerService {
  val NAME: String = "xing"
  val TOPIC_STATUS: String = s"${NAME}Status"
  val TOPIC_UPDATE: String = s"${NAME}Update"
}

trait XingScannerService extends Service with ScanStatusTopic with ProfileUpdateTopic {

  /**
    * Scrapes the Profile and storing it in a list of profiles associated with the $keyword
    *
    * @param keyword keyword the profile is associated with
    * @implict_param - String - The URL pointing to the xing profile
    * @return Done
    */
  def scrape(keyword: String): ServiceCall[String, Done]

  //used for debugging getting actual state of scanner
  def getState(keyword: String): ServiceCall[NotUsed, ProfileScannerState]

  override def descriptor: Descriptor = {
    import Service._
    //name of the service
    named(s"${XingScannerService.NAME}-scanner").withCalls(
      //api's this service provides
      restCall(Method.POST, "/api/scanner/xing/:keyword", scrape _),
      restCall(Method.GET, "/api/scanner/xing/:keyword", getState _)
    ).withTopics(
      //topics this service pulishes
      topic(XingScannerService.TOPIC_STATUS, statusTopic),
      topic(XingScannerService.TOPIC_UPDATE, updateTopic)
    )
      //added for testing, makes api public
      .withAutoAcl(true)
  }
}
