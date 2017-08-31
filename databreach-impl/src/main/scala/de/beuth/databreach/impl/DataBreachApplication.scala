package de.beuth.databreach.impl

import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.api.ServiceLocator.NoServiceLocator
import com.lightbend.lagom.scaladsl.broker.kafka.LagomKafkaComponents
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraPersistenceComponents
import com.lightbend.lagom.scaladsl.server.{LagomApplication, LagomApplicationContext, LagomApplicationLoader}
import com.softwaremill.macwire.wire
import de.beuh.databreach.api.DataBreachService
import de.beuth.databreach.scanner.api.DataBreachScannerService
import de.beuth.linkedin.scanner.api.LinkedinScannerService
import de.beuth.scan.api.ScanService
import de.beuth.xing.scanner.api.XingScannerService
import play.api.libs.ws.ahc.AhcWSComponents

/**
  * The appplication loader for the service
  */
class DataBreachLoader extends LagomApplicationLoader {

  override def load(context: LagomApplicationContext): LagomApplication =
    new DataBreachApplication(context) {
      override def serviceLocator: ServiceLocator = NoServiceLocator
    }

  override def loadDevMode(context: LagomApplicationContext): LagomApplication =
    new DataBreachApplication(context) with LagomDevModeComponents

  override def describeServices = List(
    readDescriptor[DataBreachService]
  )
}

abstract class DataBreachApplication(context: LagomApplicationContext)
  extends LagomApplication(context)
    with CassandraPersistenceComponents
    with AhcWSComponents
    with LagomKafkaComponents
{

  // wiring repository for DI
  lazy val reposiotry = wire[DataBreachReposiotry]
  // wiring scan service for DI
  lazy val scanService = serviceClient.implement[ScanService]
  // wiring linkedin for DI
  lazy val linkedinService = serviceClient.implement[LinkedinScannerService]
  // wiring xing for DI
  lazy val xingService = serviceClient.implement[XingScannerService]
  // wiring databreach scanner for DI
  lazy val dataBreachScannerService = serviceClient.implement[DataBreachScannerService]

  // Bind the services that this server provides
  override lazy val lagomServer =   serverFor[DataBreachService](wire[DataBreachImpl])

  // Register the JSON serializer registry
  override lazy val jsonSerializerRegistry = DataBreachScanSerializerRegistry

  //register the write side
  persistentEntityRegistry.register(wire[DataBreachEntity])

  //register the read side
  readSide.register(wire[DataBreachEventProcessor])
}

