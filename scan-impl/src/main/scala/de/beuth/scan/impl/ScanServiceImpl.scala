package de.beuth.scan.impl
import java.time.LocalDateTime

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import de.beuth.censys.api.{CensysQuery, CensysService}
import de.beuth.scan.api.{ScanResult, ScanService}
import org.slf4j.{Logger, LoggerFactory}

import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext
/**
  * Created by David on 06.06.17.
  */
class ScanServiceImpl(registry: PersistentEntityRegistry, system: ActorSystem, censysService: CensysService)(implicit ec: ExecutionContext, mat: Materializer)
  extends ScanService {

  private final val log: Logger = LoggerFactory.getLogger(classOf[ScanServiceImpl])

  def scan(keyword: String) = ServiceCall { _ =>
    refFor(keyword).ask(GetScan).map {
      case Some(scan) => {
        if(LocalDateTime.parse(scan.timestamp).isBefore(LocalDateTime.now().minusSeconds(2L))) {
          log.info(s"Starting Scan...")
          refFor(keyword).ask(StartScan(LocalDateTime.now().toString()))
          censysService.searchIpv4.invoke(CensysQuery("\"" + keyword + "\"")) onComplete  {
              case Success(results) => {
                results.results.foreach(r => log.info(r.toString))
                refFor(keyword).ask(FinishScan(ScanResult(LocalDateTime.now().toString(), Some(results.results))))
              }
              case Failure(ex) => {
                log.info(s"Something went wrong... $ex")
              }
            }
        }
        log.info(s"Got Something...")
        scan.result

      }
      case None => {
        log.info(s"Got Nothing...")
        refFor(keyword).ask(StartScan(LocalDateTime.now().toString()))
        ScanResult(LocalDateTime.now().toString, None)
      }
    }

  }

  private def refFor(keyword: String) = registry.refFor[ScanEntity](keyword)
}
