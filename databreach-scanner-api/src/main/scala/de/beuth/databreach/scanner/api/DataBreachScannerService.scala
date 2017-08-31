package de.beuth.databreach.scanner.api

import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.api.{Service}
import de.beuth.scanner.commons.{ScanStatusTopic}
import play.api.libs.json.{Format, Json}
import scala.collection.immutable.Seq

/**
  * Interface for the external scanner defining the topic to enable other lagom services to subscripe to it
  * and also defining the message format for this topic
  */
object DataBreachScannerService {
  val NAME = "databreachScanner"
  val TOPIC_UPDATE = s"${NAME}Update"
}

trait DataBreachScannerService extends Service with ScanStatusTopic {

  override final def descriptor = {
    import Service._
    import com.lightbend.lagom.scaladsl.api.transport.Method
    named(DataBreachScannerService.NAME).withTopics(
      topic(DataBreachScannerService.TOPIC_UPDATE, updateTopic)
    )
  }

  def updateTopic(): Topic[DataBreachUpdateEvent]
}

case class DataBreachUpdateEvent(keyword: String, results: DataBreachResults)
object DataBreachUpdateEvent {
  implicit val format: Format[DataBreachUpdateEvent] = Json.format
}

case class DataBreachResults(url: String, name: String, results: Seq[DataBreachResult])
object DataBreachResults {
  implicit val format: Format[DataBreachResults] = Json.format
}

case class DataBreachResult(email: String, password: String)
object DataBreachResult {
  implicit val format: Format[DataBreachResult] = Json.format
}