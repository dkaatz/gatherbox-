package de.beuth.proxybrowser.impl

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import de.beuth.proxybrowser.api._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by David on 20.06.17.
  */
class ProxyBrowser(registry: PersistentEntityRegistry, system: ActorSystem)(implicit ec: ExecutionContext, mat: Materializer) extends ProxyBrowserService {

  def getAvailableProxy() = ServiceCall { _ => {
      refFor().ask(GetNext()).map {
        case proxy: ProxyServer => proxy
      }
    }
  }

  def free() = ServiceCall { proxyServer => {
      refFor().ask(UpdateFree(proxyServer)).map {
        case _: Done => Done
      }
    }
  }

  def report() = ServiceCall { proxyServer => {
      refFor().ask(UpdateReported(proxyServer)).map {
        case _: Done => Done
      }
    }
  }

  private def refFor() = registry.refFor[ProxyBrowserEntity]("0")
}
