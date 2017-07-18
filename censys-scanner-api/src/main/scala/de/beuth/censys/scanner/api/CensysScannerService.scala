package de.beuth.censys.scanner.api

import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.Service.pathCall
import de.beuth.censys.api._
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
import de.beuth.scanner.commons.ScanStatusTopics


object CensysScannerService {
  val NAME = "censys"
  val TOPIC_STATUS = s"${NAME}_status"
}
/**
  * Created by David on 08.06.17.
  */
trait CensysScannerService extends Service with ScanStatusTopics {

  def search(keyword: String): ServiceCall[NotUsed, Done]

  override final def descriptor = {
    import Service._

    named("censys-scanner").withCalls(
      pathCall("/api/scanner/censys/:keyword", search _)
    ).withTopics(
      topic(CensysScannerService.TOPIC_STATUS, statusTopic)
    )
  }

}
