package de.beuth.gatherboxstream.impl

import com.lightbend.lagom.scaladsl.api.ServiceCall
import de.beuth.gatherboxstream.api.GatherboxStreamService
import de.beuth.gatherbox.api.GatherboxService

import scala.concurrent.Future

/**
  * Implementation of the GatherboxStreamService.
  */
class GatherboxStreamServiceImpl(gatherboxService: GatherboxService) extends GatherboxStreamService {
  def stream = ServiceCall { hellos =>
    Future.successful(hellos.mapAsync(8)(gatherboxService.hello(_).invoke()))
  }
}
