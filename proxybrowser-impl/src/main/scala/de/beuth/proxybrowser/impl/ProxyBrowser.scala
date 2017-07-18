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
import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by David on 20.06.17.
  */
class ProxyBrowser(registry: PersistentEntityRegistry, system: ActorSystem, wsClient: WSClient)(implicit ec: ExecutionContext, mat: Materializer) extends ProxyBrowserService {

  //on startup we fetch servers
  fetchServersFromNordVPN()

  private final val log: Logger = LoggerFactory.getLogger(classOf[ProxyBrowser])

  def getAvailableProxy() = ServiceCall { _ => {
      refFor().ask(GetNext())
    }
  }

  def free() = ServiceCall { proxyServer => {
      refFor().ask(UpdateFree(proxyServer))
    }
  }

  def report() = ServiceCall { proxyServer => {
      refFor().ask(UpdateReported(proxyServer))
    }
  }

  def add() = ServiceCall { proxyServers => {
      refFor().ask(Add(proxyServers))
    }
  }

  private def fetchServersFromNordVPN() = {
    for {
      wsResponse <- wsClient
        .url("https://nordvpn.com/wp-admin/admin-ajax.php?searchParameters[0][name]=proxy-country&searchParameters[0][value]=&searchParameters[1][name]=proxy-ports&searchParameters[1][value]=&searchParameters[2][name]=https&searchParameters[2][value]=on&offset=0&limit=500&action=getProxies")
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
      adding <- this.add().invoke(proxyServers)
    } yield adding
  }

  private def refFor() = registry.refFor[ProxyBrowserEntity]("0")
}
