package de.beuth.scan.api

import java.time.Instant

import akka.NotUsed
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.api.broker.kafka.{KafkaProperties, PartitionKeyStrategy}
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
import play.api.libs.json.{Format, Json}


object ScanService  {
  val TOPIC_NAME = "scan"
}

trait ScanService extends Service {

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
      topic(ScanService.TOPIC_NAME, scanStartedTopic)
    ).withAutoAcl(true)
  }

  def scanStartedTopic(): Topic[ScanStartedMessage]
}

case class ScanStartedMessage(keyword: String, timestamp: Instant)
object ScanStartedMessage {
  implicit val format: Format[ScanStartedMessage] = Json.format
}

case class ScanStatus(keyword: String, startedAt: Option[Instant], finished: Boolean, scanners: Seq[String])
object ScanStatus {
  implicit val format: Format[ScanStatus] = Json.format
}
