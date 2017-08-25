package de.beuth.xing.scanner.impl

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
import de.beuth.xing.scanner.api.XingScannerService
import play.api.libs.ws.ahc.AhcWSComponents

class XingScannerLoader extends LagomApplicationLoader {

  override def load(context: LagomApplicationContext): LagomApplication =
    new XingScannerApplication(context) {
      override def serviceLocator: ServiceLocator = NoServiceLocator
    }

  override def loadDevMode(context: LagomApplicationContext): LagomApplication =
    new XingScannerApplication(context) with LagomDevModeComponents

  override def describeServices = List(
    readDescriptor[XingScannerService]
  )
}

abstract class XingScannerApplication(context: LagomApplicationContext)
  extends LagomApplication(context)
    with CassandraPersistenceComponents
    with AhcWSComponents
    with LagomKafkaComponents
{

  lazy val repository = wire[XingRepository]
  lazy val scanService = serviceClient.implement[ScanService]
  lazy val ixquickScannerService = serviceClient.implement[IxquickScannerService]
  lazy val proxyBrowserService = serviceClient.implement[ProxyBrowserService]

  // Bind the services that this server provides
  override lazy val lagomServer = serverFor[XingScannerService](wire[XingScannerImpl])


  // Register the JSON serializer registry
  override lazy val jsonSerializerRegistry = XingScanSerializerRegistry

  persistentEntityRegistry.register(wire[XingScannerEntity])
  readSide.register(wire[XingEventProcessor])
}

