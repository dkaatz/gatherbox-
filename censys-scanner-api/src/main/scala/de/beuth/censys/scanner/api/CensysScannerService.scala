package de.beuth.censys.scanner.api

import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.broker.Topic
import de.beuth.censys.api._
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
import de.beuth.scanner.commons.ScanStatusTopic
import play.api.libs.json.{Format, Json}


/**
  * This Service manages the scan of the censys.io data source
  *
  * It provides one endpoint and 2 topics
  */
object CensysScannerService {
  val NAME = "censys"
  val TOPIC_STATUS = s"${NAME}Status"
  val TOPIC_UPDATE = s"${NAME}Update"
}
trait CensysScannerService extends Service with ScanStatusTopic {

  /**
    * Starting the scan for a associated keyword
    *
    * @param keyword associated keyword
    * @return done
    */
  def search(keyword: String): ServiceCall[NotUsed, Done]

  override final def descriptor = {
    import Service._
    //name of the service
    named("censys-scanner").withCalls(
      //endpoint to call the service
      pathCall("/api/scanner/censys/:keyword", search _)
    ).withTopics(
      //published topics by the service
      topic(CensysScannerService.TOPIC_STATUS, statusTopic),
      topic(CensysScannerService.TOPIC_UPDATE, updateTopic())
    )
    .withAutoAcl(true) //just enabled for testing
  }

  /**
    * Publishes the scan updates
    */
  def updateTopic(): Topic[CensysScanUpdateEvent]
}

/**
  * The messenging format for the updateTopic
  *
  * @param keyword associated keyword
  * @param ipv4 list of  results
  */
case class CensysScanUpdateEvent(keyword: String, ipv4: Seq[CensysIpv4Result])
//companion object providing serialization an deserialization
object CensysScanUpdateEvent {
  implicit val format: Format[CensysScanUpdateEvent] = Json.format
}
