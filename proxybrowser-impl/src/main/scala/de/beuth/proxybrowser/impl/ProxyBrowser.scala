package de.beuth.proxybrowser.impl

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import de.beuth.proxybrowser.api._
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json.{JsError, JsPath, JsSuccess, Json}
import play.api.libs.ws.WSClient

import scala.collection.immutable.Seq
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

/**
  * Created by David on 20.06.17.
  */
class ProxyBrowser(registry: PersistentEntityRegistry, system: ActorSystem, wsClient: WSClient)(implicit ec: ExecutionContext, mat: Materializer) extends ProxyBrowserService {

  fetchServersFromNordVPN("germany")

  private final val log: Logger = LoggerFactory.getLogger(classOf[ProxyBrowser])

  def getAvailableProxy() = ServiceCall { _ => {
      log.info("Getting Proxy");
      refFor().ask(GetNext())
    }
  }

  def free() = ServiceCall { proxyServer => {
      log.info("Freeing Proxy ${proxyServer.toFullString}");
      refFor().ask(UpdateFree(proxyServer))
    }
  }

  def report() = ServiceCall { proxyServer => {
      log.info(s"Reporting proxy: ${proxyServer.toFullString}");
      //we just fetch another server on each report to not run out of servers (limit 240 per 24 hours per IP)
      fetchProxyFromGimmeProxyAndPersistServer()
      refFor().ask(UpdateReported(proxyServer))
    }
  }

  def add() = ServiceCall { proxyServers => {
      log.info(s"Adding multiple proxies ${proxyServers.toString}");
      refFor().ask(Add(proxyServers))
    }
  }

  private def fetchProxyFromGimmeProxyAndPersistServer() = {
    log.info("Fetching a server from gimmeproxy")
    for {
      wsResponse <- wsClient
        .url("https://gimmeproxy.com/api/getProxy?get=true&supportsHttps=true&maxCheckPeriod=3600&user-agent=true&referer=true")
        .execute("GET")
      proxyServer <- {
        val parsed = Json.fromJson[ProxyServer](Json.parse(wsResponse.body))
        Future.successful(parsed match {
          case JsSuccess(r: ProxyServer, path: JsPath) => Some(r)
          case e: JsError => {
            log.error(s"Error while parsing proxy server: ${JsError.toJson(e).toString()}")
            None
          }
        })
      }
      adding <- Future.successful(if(proxyServer.isDefined) add().invoke(Seq[ProxyServer](proxyServer.get)) else Done)
    } yield proxyServer
  }

  private def fetchServersFromNordVPN(country: String) = {
    for {
      wsResponse <- wsClient
        .url(s"https://nordvpn.com/wp-admin/admin-ajax.php?searchParameters[0][name]=proxy-country&searchParameters[0][value]=$country&searchParameters[1][name]=proxy-ports&searchParameters[1][value]=&searchParameters[2][name]=https&searchParameters[2][value]=on&offset=0&limit=500&action=getProxies")
        .execute("GET")
      proxyServers <- {
        val parsed = Json.fromJson[Seq[ProxyServer]](Json.parse(wsResponse.body))

        Future.successful(parsed match {
          case JsSuccess(r: Seq[ProxyServer], path: JsPath) => r
          case e: JsError => {
            log.error(s"Error while parsing proxy servers: ${JsError.toJson(e).toString()}")
            Seq()
          }
        })
      }
      logging <- {
        log.info(s"Adding ${proxyServers.length} ProxyServers...")
        Future.successful(Done)
      }
      //adding <- this.add().invoke(proxyServers)
      adding <- add().invoke(Seq[ProxyServer](
        ProxyServer("134.213.208.187", 5007, "UK"),
        ProxyServer("164.132.231.172", 80, "France"),
        ProxyServer("83.247.7.22", 80, "Netherlands"),
        ProxyServer("213.152.165.13", 3128, "Netherlands"),
        ProxyServer("89.38.151.64", 1189, "France"),
        ProxyServer("164.132.231.172", 8080, "France"),
        ProxyServer("134.213.148.8", 3130, "UK"),
        ProxyServer("52.30.137.0", 80, "Ireland")
      ))
    } yield adding
  }

  private def refFor() = registry.refFor[ProxyBrowserEntity]("0")
}
