package de.beuth.scan.api

import java.time.Instant

import akka.NotUsed
import com.lightbend.lagom.scaladsl.api.broker.Topic
import de.beuth.scanner.commons._
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
import play.api.libs.json.{Format, Json}


object ScanService  {
  val TOPIC_STATUS = "scan_status"
}

trait ScanService extends Service with ScanStatusTopics{

  def scan(keyword: String): ServiceCall[NotUsed, ScanStatus]
  def register(keyword: String, name: String): ServiceCall[NotUsed, ScanStatus]
  def unregister(keyword: String, name: String): ServiceCall[NotUsed, ScanStatus]
  def update(keyword: String, name: String, status: String): ServiceCall[NotUsed, ScanStatus]

  override final def descriptor = {
    import Service._
    named("scan").withCalls(
      pathCall("/api/scan/:keyword", scan _),
      pathCall("/api/scan/:keyword/register/:name", register _),
      pathCall("/api/scan/:keyword/unregister/:name", unregister _),
      pathCall("/api/scan/:keyword/update/:name/:status", update _)
    ).withTopics(
      topic(ScanService.TOPIC_STATUS, statusTopic)
    ).withAutoAcl(true)
  }
}

case class ScanStatus(keyword: String, startedAt: Option[Instant], finished: Boolean, scanners: Seq[String])
object ScanStatus {
  implicit val format: Format[ScanStatus] = Json.format
}
