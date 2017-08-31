package de.beuth.proxybrowser.api

import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.transport.Method
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.libs.ws.WSProxyServer

import scala.collection.immutable.Seq


/**
  * Interface for the ProxyBrowser service
  *
  * This service fetches on application start automatically available proxy servers from nordvpn.com
  * furthermore this service is used to keep track of available proxy servers to ensure that other services do not use
  * not working services twice and that the overall system does not use the same proxy server multiple times in parallel.
  * This is important to ensure that the application gets not locked out by datasoruces such as google, ixquick etc.
  *
  * The core functionality is diveded in 4 methods wich allow:
  *   - adding new proxy servers
  *   - report proxy servers as not working
  *   - getting the next available proxy server and mark them as in use
  *   - free servers that were previously marked as in use
  */
trait ProxyBrowserService extends Service {

  /**
    * Gets the next free proxy server, removes him from the list of free servers and adds him to the
    * list of proxy servers in use
    *
    * @return next available [[ProxyServer]]
    */
  def getAvailableProxy(): ServiceCall[NotUsed, ProxyServer]

  /**
    * Expects a ProxyServer in the Body of the request and adds this proxyserver to the list of free servers if
    * he was in use before
    *
    * @return Done on success
    */
  def free(): ServiceCall[ProxyServer, Done]

  /**
    * Expects a ProxyServer in the Body of the request and adds this proxyserver to the list of not working servers,
    * a server that may get reported needs to be inuse before otherwise an report is an invalid request since if the
    * server was not used there is no way to tell if he is working or not
    *
    * @return done on success
    */
  def report(): ServiceCall[ProxyServer, Done]

  /**
    * Expects a list of ProxyServers in the Body of the request wich will get added to the list of free servers
    *
    * @return done on success
    */
  def add(): ServiceCall[Seq[ProxyServer], Done]

  /**
    * Returns a list of ProxyServers that are free
    * @return
    */
  def listFree(): ServiceCall[NotUsed, Seq[ProxyServer]]

  /**
    * Returns a list of ProxyServers that are reported
    * @return
    */
  def listReported(): ServiceCall[NotUsed, Seq[ProxyServer]]

  /**
    * Returns a list of ProxyServers that are in use
    * @return
    */
  def listInUse(): ServiceCall[NotUsed, Seq[ProxyServer]]

  override final def descriptor = {
    import Service._
    named("proxybrowser").withCalls(
      pathCall("/api/proxybrowser", getAvailableProxy),
      restCall(Method.POST,   "/api/proxybrowser/free", free),
      restCall(Method.POST,   "/api/proxybrowser/report", report),
      restCall(Method.POST,   "/api/proxybrowser/add", add),
      restCall(Method.GET,   "/api/proxybrowser/free", listFree),
      restCall(Method.GET,   "/api/proxybrowser/reported", listReported),
      restCall(Method.GET,   "/api/proxybrowser/inuse", listInUse)
    )
      .withAutoAcl(true)
  }
}

/**
  * ProxyServer representation used for inter service communication
  *
  * @param host ip or hostname of server
  * @param port port of server
  * @param country country the server is in
  */
case class ProxyServer(host: String, port: Int, country: String, username: Option[String], password: Option[String]) {
    override def toString = s"$host:$port"
    def toFullString = s"$host:$port - $country"
}

/**
  * Singleton Object holding the implict json conversion rules wich can in this case not be json.format since
  * "type" is an keyword
  */
object ProxyServer {
  implicit val write: Writes[ProxyServer] = new Writes[ProxyServer] {
    def writes(ps: ProxyServer) =
        Json.obj(
          "ip" -> ps.host,
          "port" -> ps.port.toString,
          "country" -> ps.country
        )
  }

  implicit val reads: Reads[ProxyServer] = (
    (JsPath \ "ip").read[String] and
    (JsPath \ "port").read[String] and
    (JsPath \ "country").read[String]
  )((host, port, country) => ProxyServer(host, port.toInt, country, None, None))


}

/**
  * May move to Utils or ProxyBrowser package
  *
  * Wrapper for to pass proxy servers to WSClients
  *
  * @param host ip adress or hostname of server
  * @param port port of server
  * @param protocol protocol used
  * @param principal username used
  * @param password password used
  * @param ntlmDomain ntlm domain of server
  * @param encoding char encoding of server
  * @param nonProxyHosts list of hosts that should not get proxied
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

/**
  * Companion object wich converts a [[ProxyServer]] to [[RndProxyServer]]
  */
object RndProxyServer {
  def apply(proxyServer: ProxyServer): RndProxyServer = RndProxyServer(
    host = proxyServer.host,
    port = proxyServer.port,
    None,
    principal = proxyServer.username,
    password = proxyServer.password,
    None, None, None)
}