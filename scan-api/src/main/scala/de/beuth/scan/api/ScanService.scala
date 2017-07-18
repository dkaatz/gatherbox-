package de.beuth.scan.api

import java.time.Instant

import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.Service.pathCall
import com.lightbend.lagom.scaladsl.api.broker.Topic
import de.beuth.scanner.commons._
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
import play.api.libs.json.{Format, Json}


object ScanService  {
  val TOPIC_STATUS = "scan_status"
}

trait ScanService extends Service with ScanStatusTopics {

  def startScan(keyword: String): ServiceCall[NotUsed, Done]
  def getScanStatus(keyword: String): ServiceCall[NotUsed, ScanStatus]


  override final def descriptor = {
    import Service._
    named("scan").withCalls(
      pathCall("/api/scan/:keyword/start", startScan _),
      pathCall("/api/scan/:keyword/status", getScanStatus _)
    ).withTopics(
      topic(ScanService.TOPIC_STATUS, statusTopic)
    ).withAutoAcl(true)
  }
}

case class ScanStatus(keyword: String, startedAt: Option[Instant], scanner: Seq[ScannerStatus])
object ScanStatus{
  implicit val format: Format[ScanStatus] = Json.format
}

case class ScannerStatus(name: String, startedAt: Option[Instant], finished: Boolean)
object ScannerStatus{
  implicit val format: Format[ScannerStatus] = Json.format
}
