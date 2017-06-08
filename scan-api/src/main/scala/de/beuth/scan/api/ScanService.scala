package de.beuth.scan.api

import akka.NotUsed
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}


trait ScanService extends Service {

  def scan(keyword: String): ServiceCall[NotUsed, ScanResult]

  override final def descriptor = {
    import Service._
    named("scan").withCalls(
      pathCall("/api/scan/:keyword", scan _)
    ).withAutoAcl(true)
  }
}