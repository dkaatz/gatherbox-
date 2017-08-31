package de.beuth.proxybrowser.impl

import akka.stream.Materializer
import akka.{Done}
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.InvalidCommandException
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import de.beuth.proxybrowser.api._
import org.slf4j.{Logger, LoggerFactory}
import play.api.{ Configuration}
import play.api.libs.json.{JsError, JsPath, JsSuccess, Json}
import play.api.libs.ws.WSClient

import scala.collection.immutable.Seq
import scala.collection.{Seq => DefaultSeq}
import scala.concurrent.{ExecutionContext, Future}

/**
  * The implmenetation of the Proxy Browser Service
  */
class ProxyBrowser(registry: PersistentEntityRegistry, wsClient: WSClient, config: Configuration)(implicit ec: ExecutionContext, mat: Materializer)
  extends ProxyBrowserService {

  private final val log: Logger = LoggerFactory.getLogger(classOf[ProxyBrowser])

  /**
    * Adding private servers from configuration
    */
  addPrivateServers

  def listFree() = ServiceCall {
    _ => {
      log.info("Listing Free Proxies");
      refFor().ask(ListFree)
    }
  }

  def listReported() = ServiceCall {
    _ => {
      log.info("Listing Reported Proxies");
      refFor().ask(ListReported)
    }
  }

  def listInUse() = ServiceCall {
    _ => {
      log.info("Listing InUse Proxies");
      refFor().ask(ListInUse)
    }
  }

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

  /**
    * Fetching Server from gimmeproxy.com and persits them to the Perstintent Entity
    *
    * @return Done
    */
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

  private def addGimmeProxyServers(amount: Int): Unit = {
    for (i <- 0 to amount)
      fetchProxyFromGimmeProxyAndPersistServer()
  }

  /**
    * Adding private servers from configuration to the {ProxyBrowserEntity}
    *
    * @return Done
    */
  private def addPrivateServers  =
    for {
      wait <- Future {
        Thread.sleep(500)
        Done
      }
      add <- {
        val servers = config.get[DefaultSeq[String]]("proxy-server").map( string => {
          val Array(host: String, port: String, username: String, password: String) = string.split(":")
          ProxyServer(host, port.toInt, "Unknown", Some(username), Some(password))
        }).to[Seq]
        add.invoke(servers)
      }
    } yield add


  /**
    * Fetching server for a specific country from NordVPN.com
    * @param country Country where the server should be from
    * @return Done
    */
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
          add().invoke(proxyServers)
      }
    } yield proxyServersAdded
  }

  private def refFor() = registry.refFor[ProxyBrowserEntity]("0")
}
