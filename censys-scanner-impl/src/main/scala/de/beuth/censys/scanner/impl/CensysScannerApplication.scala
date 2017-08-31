package de.beuth.censys.scanner.impl

import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.api.ServiceLocator.NoServiceLocator
import com.lightbend.lagom.scaladsl.broker.kafka.LagomKafkaComponents
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraPersistenceComponents
import com.lightbend.lagom.scaladsl.server.{LagomApplication, LagomApplicationContext, LagomApplicationLoader, LagomServer}
import com.softwaremill.macwire.wire
import de.beuth.scan.api.ScanService
import de.beuth.censys.api.CensysService
import de.beuth.censys.scanner.api.CensysScannerService
import play.api.libs.ws.ahc.AhcWSComponents

/**
  * This is the Application Loader for the Censys Service
  */
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
    with LagomKafkaComponents
{
  //load the scan service for DI
  lazy val scanService = serviceClient.implement[ScanService]
  //load the censys service for DI
  lazy val censysService = serviceClient.implement[CensysService]

  //bind the service interface to the implementation
  override lazy val lagomServer = serverFor[CensysScannerService](wire[CensysScannerImpl])

  // Register the JSON serializer registry
  override lazy val jsonSerializerRegistry = ScanSerializerRegistry

  // Register the  persistent entity
  persistentEntityRegistry.register(wire[CensysScannerEntity])
}
