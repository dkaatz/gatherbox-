package de.beuth.profile.scanner.impl

import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.api.ServiceLocator.NoServiceLocator
import com.lightbend.lagom.scaladsl.broker.kafka.LagomKafkaComponents
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraPersistenceComponents
import com.lightbend.lagom.scaladsl.server.{LagomApplication, LagomApplicationContext, LagomApplicationLoader, LagomServer}
import com.softwaremill.macwire.wire
import de.beuth.ixquick.scanner.api.IxquickScannerService
import de.beuth.profile.scanner.api.ProfileScannerService
import de.beuth.proxybrowser.api.ProxyBrowserService
//import de.beuth.proxybrowser.api.ProxyBrowserService
import de.beuth.scan.api.ScanService
import play.api.libs.ws.ahc.AhcWSComponents

class ProfileScannerLoader extends LagomApplicationLoader {

  override def load(context: LagomApplicationContext): LagomApplication =
    new ProfileScannerApplication(context) {
      override def serviceLocator: ServiceLocator = NoServiceLocator
    }

  override def loadDevMode(context: LagomApplicationContext): LagomApplication =
    new ProfileScannerApplication(context) with LagomDevModeComponents

  override def describeServices = List(
    readDescriptor[ProfileScannerService]
  )
}

abstract class ProfileScannerApplication(context: LagomApplicationContext)
  extends LagomApplication(context)
    with CassandraPersistenceComponents
    with AhcWSComponents
    with LagomKafkaComponents
{

  lazy val scanService = serviceClient.implement[ScanService]
  lazy val ixquickScannerService = serviceClient.implement[IxquickScannerService]
  lazy val proxyBrowserService = serviceClient.implement[ProxyBrowserService]

  // Bind the services that this server provides
  override lazy val lagomServer = serverFor[ProfileScannerService](wire[ProfileScannerImpl])


  // Register the JSON serializer registry
  override lazy val jsonSerializerRegistry = ProfileScanSerializerRegistry

  persistentEntityRegistry.register(wire[ProfileScannerEntity])
}

