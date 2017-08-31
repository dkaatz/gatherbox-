package de.beuh.databreach.api

import akka.Done
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
import de.beuth.databreach.scanner.api.{DataBreachResult, DataBreachResults, DataBreachUpdateEvent}
import de.beuth.scanner.commons.{Profile, ScanStatusTopic}
import play.api.libs.json.{Format, Json}

import scala.collection.immutable.Seq

/**
  * This services manages the state of the scan by an the external data breach scanner service
  *
  * It provides one endpoint and three topics to subscripe
  */
object DataBreachService {
  val NAME = "databreach"
  val TOPIC_STATUS = s"${NAME}Status"
  val TO_SCAN = s"${NAME}ToScan"
  val UPDATE = s"${NAME}Update"
}


trait DataBreachService extends Service with ScanStatusTopic {

  /**
    * Starts a scan for a given profile on the external scanner
    *
    * @param keyword associated keyword
    * @return done
    */
  def scanForProfile(keyword: String): ServiceCall[Profile, Done]

  override final def descriptor = {
    import Service._
    import com.lightbend.lagom.scaladsl.api.transport.Method
    named("databreach").withCalls(
      //starts the scan for a given profile
      restCall(Method.POST,   "/api/databreach/:keyword", scanForProfile _)
    ).withTopics(
      topic(DataBreachService.TOPIC_STATUS, statusTopic), // the topic telling other scanners about the scan status
      topic(DataBreachService.TO_SCAN, toScanTopic), // the topic telling the foreign scanner what to scan
      topic(DataBreachService.UPDATE, updateTopic()) // the topic publishing scan results
    )
      .withAutoAcl(true) //just enabled for testing
  }

  /**
    * The topic telling the external scanner what to scan for
    */
  def toScanTopic(): Topic[DataBreachToScanEvent]

  /**
  * The update event of the scanner publishing results
  */
  def updateTopic(): Topic[DataBreachCombinedUpdatedEvent]
}

/**
  * Message format for toScanTopic
  *
  * @param keyword associated keyword
  * @param url  the url of the profile
  * @param name the name to scan for
  */
case class DataBreachToScanEvent(keyword: String, url: String, name: String)
object DataBreachToScanEvent {
  implicit val format: Format[DataBreachToScanEvent] = Json.format
}

/**
  * The message format for the update topic
  * @param keyword
  * @param results
  */
case class DataBreachCombinedUpdatedEvent(keyword: String, results: DataBreachCombinedResults)
object DataBreachCombinedUpdatedEvent {
  implicit val format: Format[DataBreachCombinedUpdatedEvent] = Json.format
}

/**
  * The combined results object of a scan, representing the final scan result
  *
  * Combined means that results from firstname and lastname scans are joined in this object to gether
  *
  * @param url url of profile that got scanned
  * @param firstname the firstname of the profile owner
  * @param lastname the lastname of the profile owner
  * @param scanned a value indicating that this resource got scanned
  * @param results the actual results containing email and password
  */
case class DataBreachCombinedResults(url: String, firstname: String, lastname: String, scanned: Boolean, results: Seq[DataBreachResult])
object DataBreachCombinedResults {
  implicit val format: Format[DataBreachCombinedResults] = Json.format
}