package de.beuth.censys.scanner.impl

import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.stream.scaladsl.Flow
import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import de.beuth.censys.api.{CensysIpv4Result, CensysIpv4SearchResult, CensysQuery, CensysService}
import de.beuth.censys.scanner.api.CensysScannerService
import de.beuth.scan.api.{ScanService}
import de.beuth.scanner.commons.{ScanStartedEvent, ScanStatusEvent}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.immutable.Seq
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration

object CensysScanner {
  val name: String = "Censys-Scanner"
}

class CensysScannerImpl(registry: PersistentEntityRegistry, censysService: CensysService, scanService: ScanService)(implicit ec: ExecutionContext) extends CensysScannerService {
  import scala.concurrent.ExecutionContext.Implicits.global

  private final val log: Logger =
    LoggerFactory.getLogger(classOf[CensysScannerImpl])

    scanService.statusTopic().subscribe.atLeastOnce(
      Flow[ScanStatusEvent].mapAsync(1) {
        case ev: ScanStartedEvent => {
          log.info("Received ScanStartedMessage: " + ev.keyword)
          //@todo remove direct service calls ( moving to full message broker communication )
          for {
            register <- scanService.register(ev.keyword, CensysScanner.name).invoke()
            update <- scanService.update(ev.keyword, CensysScanner.name, "Scanning").invoke()
            search <- this.search(ev.keyword).invoke()
          } yield search
        }
        case _ => Future.successful(Done)
      }
    )
//  scanService.statusTopic().subscribe.atLeastOnce(
//    Flow[ScanStatusEvent].map{ msg =>
////      log.info("Received ScanStartedMessage: " + msg.keyword)
////      scanService.register(msg.keyword, CensysScanner.name).invoke().onComplete {
////        _ => {
////          scanService.update(msg.keyword, CensysScanner.name, "Scanning").invoke()
////          this.search(msg.keyword).invoke()
////        }
////      }
////
////      log.info("Searching: " + msg.keyword)
////
////      Done
//    }
//  )

  def search(keyword: String) = ServiceCall { _ => {
    log.info("Start Searching with: " + keyword)
      refFor(keyword).ask(StartScan(Instant.now())).map {
        case _: Done =>  {
          log.info("Start Searching with: " + keyword)
          Await.result(refFor(keyword).ask(UpdateScan(ipv4 = scan(keyword))).map {
            case _: Done => {
              log.info("finished Searching with: " + keyword)
              scanService.update(keyword, CensysScanner.name, "Scanned").invoke()
            }
          }, Duration(10L, TimeUnit.SECONDS))
          Done
        }
      }
    }
  }

  //@todo may consider to change this (syncrhonus api call to censys.io...
  private def scan(keyword: String, page: Int = 1): Seq[CensysIpv4Result] = {
    log.info("scanning... "  + keyword + " page " + page)
    Await.result((censysService.searchIpv4().invoke(CensysQuery(query = "\"" + keyword + "\"", page = page)) map {
      case result: CensysIpv4SearchResult if result.metadata.pages > result.metadata.page => {
        log.info("Count: " + result.metadata.count  + "- Page:" +  result.metadata.page + " of " +  result.metadata.pages)
        result.results ++ scan(keyword, page+1)
      }
      case result: CensysIpv4SearchResult if result.metadata.pages == result.metadata.page => result.results
    }).recover{ case e => {
      log.info("Failed:" + e.toString())
      Seq[CensysIpv4Result]()
    }}, Duration(10L, TimeUnit.SECONDS))
  }

  private def refFor(keyword: String) = registry.refFor[CensysScannerEntity](keyword)
}