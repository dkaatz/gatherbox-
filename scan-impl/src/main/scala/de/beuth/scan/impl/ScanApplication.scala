package de.beuth.scan.impl

import de.beuth.scan.api._
import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.api.ServiceLocator.NoServiceLocator
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraPersistenceComponents
import com.lightbend.lagom.scaladsl.server._
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import play.api.libs.ws.ahc.AhcWSComponents
import com.softwaremill.macwire._
import de.beuth.censys.api.CensysService

abstract class ScanApplication(context: LagomApplicationContext)
  extends LagomApplication(context)
    with CassandraPersistenceComponents
    with AhcWSComponents {

  lazy val censysService = serviceClient.implement[CensysService]
  lazy val scanService = serviceClient.implement[ScanService]

  // Bind the services that this server provides
  override lazy val lagomServer = LagomServer.forServices(
    bindService[ScanService].to(wire[ScanServiceImpl])
  )

  // Register the JSON serializer registry
  override lazy val jsonSerializerRegistry = ScanSerializerRegistry

  // Register the gatherbox persistent entity
  persistentEntityRegistry.register(wire[ScanEntity])
}
class ScanLoader extends LagomApplicationLoader {

  override def load(context: LagomApplicationContext): LagomApplication =
    new ScanApplication(context) {

      override def serviceLocator: ServiceLocator = NoServiceLocator
    }

  override def loadDevMode(context: LagomApplicationContext): LagomApplication =
    new ScanApplication(context) with LagomDevModeComponents

  override def describeServices = List(
    readDescriptor[ScanService]
  )
}


