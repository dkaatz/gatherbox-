package de.beuth.scan.api

import java.time.Instant

import akka.{Done, NotUsed}
import de.beuth.scanner.commons._
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
import play.api.libs.json.{Format, Json}

/**
  * The scan Service manages the overall scan state of a scan
  *
  * it provieds two endpoints one for starting a scan and one for getting the state of the scan
  */
object ScanService  {
  val NAME = "scan"
  val TOPIC_STATUS = s"${NAME}Status"
}
trait ScanService extends Service with ScanStatusTopic {

  /**
    * Starts the scan
    */
  def startScan(keyword: String): ServiceCall[NotUsed, Done]

  /**
    * Gets the state of the scan
    */
  def getScanStatus(keyword: String): ServiceCall[NotUsed, ScanStatus]

  /**
    * Descriptor for the service
    */
  override final def descriptor = {
    import Service._
    //name of the service
    named(ScanService.NAME).withCalls(
      //endpoints
      pathCall("/api/scan/:keyword/start", startScan _),
      pathCall("/api/scan/:keyword/status", getScanStatus _)
    ).withTopics(
      //topics
      topic(ScanService.TOPIC_STATUS, statusTopic)
    ).withAutoAcl(true)
  }
}

/**
  * Format for the overall scanner status
  * @param keyword keyword
  * @param startedat when did the scan got started
  * @param scanner list of scanners
  */
case class ScanStatus(keyword: String, startedat: Option[Instant], scanner: Seq[ScannerStatus])
object ScanStatus{
  implicit val format: Format[ScanStatus] = Json.format
}

/**
  * A state representation for another scanner
  *
  * @param name name of the scanner
  * @param startedat date when the scanner started
  * @param finished did the scanner finished ?
  */
case class ScannerStatus(name: String, startedat: Option[Instant], finished: Boolean)
object ScannerStatus{
  implicit val format: Format[ScannerStatus] = Json.format
}
