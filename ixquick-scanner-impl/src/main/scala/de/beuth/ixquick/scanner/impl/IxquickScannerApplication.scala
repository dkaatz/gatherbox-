package de.beuth.ixquick.scanner.impl

import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.api.ServiceLocator.NoServiceLocator
import com.lightbend.lagom.scaladsl.broker.kafka.LagomKafkaComponents
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraPersistenceComponents
import com.lightbend.lagom.scaladsl.server.{LagomApplication, LagomApplicationContext, LagomApplicationLoader, LagomServer}
import com.softwaremill.macwire.wire
import de.beuth.ixquick.scanner.api.IxquickScannerService
import de.beuth.proxybrowser.api.ProxyBrowserService
import de.beuth.scan.api.ScanService
import play.api.libs.ws.ahc.AhcWSComponents

/**
  * THe application loader of the service
  */
class IxquickScannerLoader extends LagomApplicationLoader {

  override def load(context: LagomApplicationContext): LagomApplication =
    new IxquickScannerApplication(context) {
      override def serviceLocator: ServiceLocator = NoServiceLocator
    }

  override def loadDevMode(context: LagomApplicationContext): LagomApplication =
    new IxquickScannerApplication(context) with LagomDevModeComponents

  override def describeServices = List(
    readDescriptor[IxquickScannerService]
  )
}

abstract class IxquickScannerApplication(context: LagomApplicationContext)
  extends LagomApplication(context)
    with CassandraPersistenceComponents
    with AhcWSComponents
    with LagomKafkaComponents
{

  //wire scan service for DI
  lazy val scanService = serviceClient.implement[ScanService]
  //wire proxy browser service for DI
  lazy val proxyBrowserService = serviceClient.implement[ProxyBrowserService]

  // Bind the services that this server provides
  override lazy val lagomServer = serverFor[IxquickScannerService](wire[IxquickScannerImpl])

  // Register the JSON serializer registry
  override lazy val jsonSerializerRegistry = IxquickScanSerializerRegistry

  //register the write side
  persistentEntityRegistry.register(wire[IxquickScannerEntity])
}

