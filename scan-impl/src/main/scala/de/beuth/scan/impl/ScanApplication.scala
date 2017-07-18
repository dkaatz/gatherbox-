package de.beuth.scan.impl

import de.beuth.scan.api._
import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.api.ServiceLocator.NoServiceLocator
import com.lightbend.lagom.scaladsl.broker.kafka.LagomKafkaComponents
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraPersistenceComponents
import com.lightbend.lagom.scaladsl.server._
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import play.api.libs.ws.ahc.AhcWSComponents
import com.softwaremill.macwire._
import de.beuth.censys.scanner.api.CensysScannerService
import de.beuth.ixquick.scanner.api.IxquickScannerService
import de.beuth.profile.scanner.api.ProfileScannerService

abstract class ScanApplication(context: LagomApplicationContext)
  extends LagomApplication(context)
    with CassandraPersistenceComponents
    with AhcWSComponents
    with LagomKafkaComponents
{

  lazy val censysScannerService = serviceClient.implement[CensysScannerService]
  lazy val profileScannerService = serviceClient.implement[ProfileScannerService]
  lazy val ixquickScannerService = serviceClient.implement[IxquickScannerService]
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


