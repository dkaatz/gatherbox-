package de.beuth.gatherbox.impl

import akka.Done
import akka.stream.scaladsl.Flow
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import de.beuth.gatherbox.api.GatherboxService
import de.beuth.censys.api.{CensysQuery, CensysService}
import de.beuth.scan.api.{ScanService, ScanStartedMessage}
import de.beuth.scan.impl.ScanServiceImpl
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/**
  * Implementation of the GatherboxService.
  */
class GatherboxServiceImpl(persistentEntityRegistry: PersistentEntityRegistry, scanService: ScanService) extends GatherboxService {

  private final val log: Logger =
    LoggerFactory.getLogger(classOf[GatherboxServiceImpl])


  override def hello(id: String) = ServiceCall { _ =>
    // Look up the gatherbox entity for the given ID.
    val ref = persistentEntityRegistry.refFor[GatherboxEntity](id)
//    val result = censysService.searchIpv4.invoke(CensysQuery("\"Rocket-Internet SE\""))

//    result.onComplete {
//      case Success(res) => {
//        res.results.foreach(r => log.info(r.toString))
//      }
//      case Failure(ex) => {
//
//        log.info(s"Something went wrong... $ex")
//
//      }
//     }
//    result.map( censysResult => {
//      log.info("Something")
//      censysResult.foreach(res => {
//        log.info(res.ip)
//      })
//    }
//    )

    // Ask the entity the Hello command.
    ref.ask(Hello(id, None))
  }

  override def useGreeting(id: String) = ServiceCall { request =>
    // Look up the gatherbox entity for the given ID.
    val ref = persistentEntityRegistry.refFor[GatherboxEntity](id)

    // Tell the entity to use the greeting message specified.
    ref.ask(UseGreetingMessage(request.message))
  }
}
