package de.beuth.ixquick.scanner.api


import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.Service.pathCall
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
/**
  * Created by David on 13.06.17.
  */
trait IxquickScannerService extends Service {
  
  def scanLinkedin(keyword: String): ServiceCall[NotUsed, Done]
  def scanXing(keyword: String): ServiceCall[NotUsed, Done]

  override final def descriptor = {
    import Service._

    named("ixquick-scanner").withCalls(
      pathCall("/api/scanner/ixquick/linkedin/:keyword", scanLinkedin _),
      pathCall("/api/scanner/ixquick/xing/:keyword", scanXing _)
    ).withAutoAcl(true)
  }

}
