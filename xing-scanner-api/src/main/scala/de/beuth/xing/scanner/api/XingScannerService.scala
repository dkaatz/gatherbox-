package de.beuth.xing.scanner.api



import akka.{Done}
import com.lightbend.lagom.scaladsl.api.transport.Method
import com.lightbend.lagom.scaladsl.api.{Descriptor, Service, ServiceCall}
import de.beuth.scanner.commons.{ProfileUpdateTopic, ScanStatusTopic}

object XingScannerService {
  val NAME: String = "xing"
  val TOPIC_STATUS: String = s"${NAME}Status"
  val TOPIC_UPDATE: String = s"${NAME}Update"
}

trait XingScannerService extends Service with ScanStatusTopic with ProfileUpdateTopic {

  def scrape(keyword: String): ServiceCall[String, Done]

  override def descriptor: Descriptor = {
    import Service._

    named("profile-scanner").withCalls(
      restCall(Method.POST, "/api/scanner/xing/:keyword", scrape _)
    ).withTopics(
      topic(XingScannerService.TOPIC_STATUS, statusTopic),
      topic(XingScannerService.TOPIC_UPDATE, updateTopic)
    ).withAutoAcl(true)
  }
}
