package de.beuth.proxybrowser.api

import akka.{NotUsed, Done}
import com.lightbend.lagom.scaladsl.api.transport.Method
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
import play.api.libs.json.{Format, Json}

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