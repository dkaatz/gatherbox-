package de.beuth.proxybrowser.impl

import com.lightbend.lagom.scaladsl.api.ServiceLocator
import com.lightbend.lagom.scaladsl.api.ServiceLocator.NoServiceLocator
import com.lightbend.lagom.scaladsl.devmode.LagomDevModeComponents
import com.lightbend.lagom.scaladsl.persistence.cassandra.CassandraPersistenceComponents
import com.lightbend.lagom.scaladsl.server.{LagomApplication, LagomApplicationContext, LagomApplicationLoader, LagomServer}
import com.softwaremill.macwire.wire
import de.beuth.proxybrowser.api.ProxyBrowserService
import play.api.libs.ws.ahc.AhcWSComponents

/**
  * Created by David on 20.06.17.
  */


class ProxyBrowserLoader extends LagomApplicationLoader {

  override def load(context: LagomApplicationContext): LagomApplication =
    new ProxyBrowserApplication(context) {
      override def serviceLocator: ServiceLocator = NoServiceLocator
    }

  override def loadDevMode(context: LagomApplicationContext): LagomApplication =
    new ProxyBrowserApplication(context) with LagomDevModeComponents

  override def describeServices = List(
    readDescriptor[ProxyBrowserService]
  )
}

abstract class ProxyBrowserApplication(context: LagomApplicationContext)
  extends LagomApplication(context)
    with CassandraPersistenceComponents
    with AhcWSComponents
{

  // Bind the services that this server provides
  override lazy val lagomServer = LagomServer.forServices(
    bindService[ProxyBrowserService].to(wire[ProxyBrowser])
  )

  // Register the JSON serializer registry
  override lazy val jsonSerializerRegistry = ProxyBorwserSerializerRegistry

  persistentEntityRegistry.register(wire[ProxyBrowserEntity])
}

