package de.beuth.gatherboxstream.api

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}

/**
  * The gatherbox stream interface.
  *
  * This describes everything that Lagom needs to know about how to serve and
  * consume the GatherboxStream service.
  */
trait GatherboxStreamService extends Service {

  def stream: ServiceCall[Source[String, NotUsed], Source[String, NotUsed]]

  override final def descriptor = {
    import Service._

    named("gatherbox-stream")
      .withCalls(
        namedCall("stream", stream)
      ).withAutoAcl(true)
  }
}

