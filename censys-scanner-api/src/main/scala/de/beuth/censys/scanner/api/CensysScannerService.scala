package de.beuth.censys.scanner.api

import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.Service.pathCall
import de.beuth.censys.api._
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}

/**
  * Created by David on 08.06.17.
  */
trait CensysScannerService extends Service {

  def search(keyword: String): ServiceCall[NotUsed, Done]

  override final def descriptor = {
    import Service._

    named("censys-scanner").withCalls(
      pathCall("/api/scanner/censys/:keyword", search _)
    )
  }

}
