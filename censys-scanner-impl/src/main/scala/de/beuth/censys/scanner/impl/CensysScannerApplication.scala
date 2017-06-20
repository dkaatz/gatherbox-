package de.beuth.censys.scanner.impl

import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.api.ServiceLocator.NoServiceLocator
import com.lightbend.lagom.scaladsl.broker.kafka.LagomKafkaClientComponents
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraPersistenceComponents
import com.lightbend.lagom.scaladsl.server.{LagomApplication, LagomApplicationContext, LagomApplicationLoader, LagomServer}
import com.softwaremill.macwire.wire
import de.beuth.scan.api.ScanService
import de.beuth.censys.api.CensysService
import de.beuth.censys.scanner.api.CensysScannerService
import play.api.libs.ws.ahc.AhcWSComponents


class CensysScannerLoader extends LagomApplicationLoader {

  override def load(context: LagomApplicationContext): LagomApplication =
    new CensysScannerApplication(context) {
      override def serviceLocator: ServiceLocator = NoServiceLocator
    }

  override def loadDevMode(context: LagomApplicationContext): LagomApplication =
    new CensysScannerApplication(context) with LagomDevModeComponents

  override def describeServices = List(
    readDescriptor[CensysScannerService]
  )
}

abstract class CensysScannerApplication(context: LagomApplicationContext)
  extends LagomApplication(context)
    with CassandraPersistenceComponents
    with AhcWSComponents
    with LagomKafkaClientComponents
{

  lazy val scanService = serviceClient.implement[ScanService]
  lazy val censysService = serviceClient.implement[CensysService]

  // Bind the services that this server provides
  override lazy val lagomServer = LagomServer.forServices(
    bindService[CensysScannerService].to(wire[CensysScannerImpl])
  )

  // Register the JSON serializer registry
  override lazy val jsonSerializerRegistry = ScanSerializerRegistry

  // Register the gatherbox persistent entity
  persistentEntityRegistry.register(wire[CensysScannerEntity])
}
