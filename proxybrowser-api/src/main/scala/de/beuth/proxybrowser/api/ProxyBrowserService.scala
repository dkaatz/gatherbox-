package de.beuth.proxybrowser.api

import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.transport.Method
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
import play.api.libs.json.{Format, Json}
import play.api.libs.ws.WSProxyServer

import scala.collection.immutable.Seq

/**
  * Created by David on 20.06.17.
  */
trait ProxyBrowserService extends Service {

  def getAvailableProxy(): ServiceCall[NotUsed, ProxyServer]
  def free(): ServiceCall[ProxyServer, Done]
  def report(): ServiceCall[ProxyServer, Done]

  override final def descriptor = {
    import Service._

    named("proxybrowser").withCalls(
      pathCall("/api/proxybrowser", getAvailableProxy),
      restCall(Method.POST,   "/api/proxybrowser/free", free),
      restCall(Method.POST,   "/api/proxybrowser/report", report)
    ).withAutoAcl(true)
  }
}


case class ProxyServer(host: String, port: Int)
object ProxyServer {
  implicit val format: Format[ProxyServer] = Json.format[ProxyServer]
}

/**
  * May move to Utils or ProxyBrowser package
  *
  * @param host
  * @param port
  * @param protocol
  * @param principal
  * @param password
  * @param ntlmDomain
  * @param encoding
  * @param nonProxyHosts
  */
case class RndProxyServer(host: String,
                          port: Int,
                          protocol: Option[String],
                          principal: Option[String],
                          password: Option[String],
                          ntlmDomain: Option[String],
                          encoding: Option[String],
                          nonProxyHosts: Option[Seq[String]]
                         ) extends WSProxyServer

object RndProxyServer {
  def apply(proxyServer: ProxyServer): RndProxyServer = RndProxyServer(host = proxyServer.host, port = proxyServer.port, None, None, None, None, None, None)
}