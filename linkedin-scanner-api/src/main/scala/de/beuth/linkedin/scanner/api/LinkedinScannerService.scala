package de.beuth.linkedin.scanner.api

import akka.Done
import com.lightbend.lagom.scaladsl.api.{Descriptor, Service, ServiceCall}
import de.beuth.scanner.commons.ScanStatusTopics

object LinkedinScannerService {
  val TOPIC_STATUS: String = "linkedin_status"
}

trait LinkedinScannerService extends Service {
  //with ScanStatusTopics{

  def scanProfile(): ServiceCall[String, Done]

  override def descriptor: Descriptor = {
    import Service._

    named("ixquick-scanner").withCalls(
      pathCall("/api/scanner/linkedin", scanProfile)
    ).withTopics(
      //topic(LinkedinScannerService.TOPIC_STATUS, statusTopic)
    ).withAutoAcl(true)
  }
}
