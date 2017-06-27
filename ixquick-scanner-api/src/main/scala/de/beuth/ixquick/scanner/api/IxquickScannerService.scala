package de.beuth.ixquick.scanner.api


import akka.{Done, NotUsed}
import de.beuth.scanner.commons.ScanStatusTopics
import com.lightbend.lagom.scaladsl.api.Service.pathCall
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
/**
  * Created by David on 13.06.17.
  */

object IxquickScannerService {
  val TOPIC_STATUS = "ixquick_status"
  val TOPIC_UPDATE = "ixquick_update"
}

trait IxquickScannerService extends Service with ScanStatusTopics {
//  extends ScanServiceWithDefaultTopics {

  def scanLinkedin(keyword: String): ServiceCall[NotUsed, Done]
  def scanXing(keyword: String): ServiceCall[NotUsed, Done]

  override final def descriptor = {
    import Service._

    named("ixquick-scanner").withCalls(
      pathCall("/api/scanner/ixquick/linkedin/:keyword", scanLinkedin _),
      pathCall("/api/scanner/ixquick/xing/:keyword", scanXing _)
    ).withTopics(
      topic(IxquickScannerService.TOPIC_STATUS, statusTopic)
    ).withAutoAcl(true)
  }
}


