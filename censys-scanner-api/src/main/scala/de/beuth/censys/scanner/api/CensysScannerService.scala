package de.beuth.censys.scanner.api

import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.broker.Topic
import de.beuth.censys.api._
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
import de.beuth.scanner.commons.ScanStatusTopic
import play.api.libs.json.{Format, Json}



object CensysScannerService {
  val NAME = "censys"
  val TOPIC_STATUS = s"${NAME}Status"
  val TOPIC_UPDATE = s"${NAME}Update"
}
/**
  * Created by David on 08.06.17.
  */
trait CensysScannerService extends Service with ScanStatusTopic {

  def search(keyword: String): ServiceCall[NotUsed, Done]

  override final def descriptor = {
    import Service._

    named("censys-scanner").withCalls(
      pathCall("/api/scanner/censys/:keyword", search _)
    ).withTopics(
      topic(CensysScannerService.TOPIC_STATUS, statusTopic),
      topic(CensysScannerService.TOPIC_UPDATE, updateTopic())
    )
  }

  def updateTopic(): Topic[CensysScanUpdateEvent]
}

case class CensysScanUpdateEvent(keyword: String, ipv4: Seq[CensysIpv4Result])
object CensysScanUpdateEvent {
  implicit val format: Format[CensysScanUpdateEvent] = Json.format
}
