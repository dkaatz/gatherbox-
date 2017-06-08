package de.beuth.gatherboxstream.impl

import com.lightbend.lagom.scaladsl.api.ServiceLocator.NoServiceLocator
import com.lightbend.lagom.scaladsl.server._
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import play.api.libs.ws.ahc.AhcWSComponents
import de.beuth.gatherboxstream.api.GatherboxStreamService
import de.beuth.gatherbox.api.GatherboxService
import com.softwaremill.macwire._

class GatherboxStreamLoader extends LagomApplicationLoader {

  override def load(context: LagomApplicationContext): LagomApplication =
    new GatherboxStreamApplication(context) {
      override def serviceLocator = NoServiceLocator
    }

  override def loadDevMode(context: LagomApplicationContext): LagomApplication =
    new GatherboxStreamApplication(context) with LagomDevModeComponents

  override def describeServices = List(
    readDescriptor[GatherboxStreamService]
  )
}

abstract class GatherboxStreamApplication(context: LagomApplicationContext)
  extends LagomApplication(context)
    with AhcWSComponents {

  // Bind the services that this server provides
  override lazy val lagomServer = LagomServer.forServices(
    bindService[GatherboxStreamService].to(wire[GatherboxStreamServiceImpl])
  )

  // Bind the GatherboxService client
  lazy val gatherboxService = serviceClient.implement[GatherboxService]
}
