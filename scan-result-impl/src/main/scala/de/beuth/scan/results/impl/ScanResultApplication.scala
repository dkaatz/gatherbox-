package de.beuth.scan.results.impl

import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.api.ServiceLocator.NoServiceLocator
import com.lightbend.lagom.scaladsl.broker.kafka.{LagomKafkaClientComponents, LagomKafkaComponents}
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraPersistenceComponents
import com.lightbend.lagom.scaladsl.server.{LagomApplication, LagomApplicationContext, LagomApplicationLoader}
import de.beuh.databreach.api.DataBreachService
import de.beuth.censys.scanner.api.CensysScannerService
import de.beuth.linkedin.scanner.api.LinkedinScannerService
import de.beuth.xing.scanner.api.XingScannerService
import play.api.libs.ws.ahc.AhcWSComponents
import com.softwaremill.macwire._
import de.beuth.scan.results.api.ScanResultService

/**
  * Application Loader
  */
abstract class ScanResultApplication(context: LagomApplicationContext)
  extends LagomApplication(context)
    with CassandraPersistenceComponents
    with AhcWSComponents
    with LagomKafkaClientComponents
{

  //wire sevices for DI
  lazy val censysScannerService = serviceClient.implement[CensysScannerService]
  lazy val linekedinScannerService = serviceClient.implement[LinkedinScannerService]
  lazy val xingScannerService = serviceClient.implement[XingScannerService]
  lazy val dataBreachService = serviceClient.implement[DataBreachService]

  // Bind the services that this server provides
  override lazy val lagomServer = serverFor[ScanResultService](wire[ScanResultImpl])

  // Register the JSON serializer registry
  override lazy val jsonSerializerRegistry = ScanResultSerializerRegistry

  // Register the gatherbox persistent entity
  persistentEntityRegistry.register(wire[ScanResultEntity])
}

class ScanResultLoader extends LagomApplicationLoader {

  override def load(context: LagomApplicationContext): LagomApplication =
    new ScanResultApplication(context) {

      override def serviceLocator: ServiceLocator = NoServiceLocator
    }

  override def loadDevMode(context: LagomApplicationContext): LagomApplication =
    new ScanResultApplication(context) with LagomDevModeComponents

  override def describeServices = List(
    readDescriptor[ScanResultService]
  )
}


