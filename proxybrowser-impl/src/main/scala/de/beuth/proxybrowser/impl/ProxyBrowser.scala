package de.beuth.proxybrowser.impl

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.InvalidCommandException
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

//  fetchServersFromNordVPN("france")
//  fetchServersFromNordVPN("germany")
//  fetchServersFromNordVPN("austria")
  fetchServersFromNordVPN("own")

  private final val log: Logger = LoggerFactory.getLogger(classOf[ProxyBrowser])

  def getAvailableProxy() = ServiceCall { _ => {
      log.info("Getting Proxy");
      refFor().ask(GetNext())
    }
  }

  def free() = ServiceCall { proxyServer => {
      log.info(s"Freeing Proxy ${proxyServer.toFullString}");
      refFor().ask(UpdateFree(proxyServer)).recover {
        case e: InvalidCommandException => Done
      }
    }
  }

  def report() = ServiceCall { proxyServer => {
      log.info(s"Reporting proxy: ${proxyServer.toFullString}");
      //we just fetch another server on each report to not run out of servers (limit 240 per 24 hours per IP)
      try {
        fetchProxyFromGimmeProxyAndPersistServer()
      } catch {
        case _: Throwable => Future.successful(Done)
      }
      refFor().ask(UpdateReported(proxyServer)).recover {
        case e: InvalidCommandException => Done
      }
    }
  }

  def add() = ServiceCall { proxyServers => {
      log.info(s"Adding multiple proxies ${proxyServers.toString}");
      refFor().ask(Add(proxyServers)).recover {
        case e: InvalidCommandException => Done
      }
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
      proxyServersAdded <- {
        if(country == "own") {
          Future.successful(Seq[ProxyServer](
            ProxyServer("50.3.134.31", 80,  "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("151.237.190.53", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("91.108.177.146", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("91.108.176.109", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("5.34.242.201", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("185.119.255.102", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("50.3.134.114", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("151.237.190.147", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("91.108.176.189", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("5.34.242.35", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("46.29.250.252", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("46.29.250.241", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("5.34.243.55", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("5.157.40.204", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("5.34.242.237", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("176.61.138.146", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("91.108.178.119", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("5.34.243.32", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("5.34.242.114", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("185.119.255.104", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("185.119.255.232", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("89.37.66.106", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("50.3.134.48", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("50.3.134.94", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("188.215.22.57", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("185.119.255.237", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("91.108.176.2", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("188.215.22.64", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("185.119.255.124", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ")),
            ProxyServer("50.3.134.55", 80, "Germany", Some("gatherbox"), Some("WZwfmTHWs5YFDsgMdDeQ"))
          ))
        } else {
          Future.successful(proxyServers)
        }
      }
      logging <- {
        log.info(s"Adding ${proxyServersAdded.length} ProxyServers...")
        Future.successful(Done)
      }
      adding <- this.add().invoke(proxyServersAdded)
//      adding <- {
//        for (a <- 0 until 10) {
//          fetchProxyFromGimmeProxyAndPersistServer()
//        }
//        Future.successful(Done)
//      }
    } yield adding
  }

  private def refFor() = registry.refFor[ProxyBrowserEntity]("0")
}
