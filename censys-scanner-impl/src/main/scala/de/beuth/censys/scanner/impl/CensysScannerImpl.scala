package de.beuth.censys.scanner.impl

import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.actor.Status.{Failure, Success}
import akka.stream.scaladsl.Flow
import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.transport.NotFound
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import de.beuth.censys.api.{CensysIpv4Result, CensysIpv4SearchResult, CensysQuery, CensysService}
import de.beuth.censys.scanner.api.CensysScannerService
import de.beuth.scan.api.{ScanService, ScanStartedMessage, ScanStatus}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.immutable.Seq
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration

object CensysScanner {
  val name: String = "Censys-Scanner"
}

class CensysScannerImpl(registry: PersistentEntityRegistry, censysService: CensysService, scanService: ScanService) extends CensysScannerService {
  import scala.concurrent.ExecutionContext.Implicits.global

  private final val log: Logger =
    LoggerFactory.getLogger(classOf[CensysScannerImpl])


//  scanService.scanStartedTopic().subscribe.atLeastOnce(
//    Flow[ScanStartedMessage].map{ msg =>
//      log.info("Received ScanStartedMessage: " + msg.keyword)
//      scanService.register(msg.keyword, CensysScanner.name).invoke().onComplete {
//        _ => {
//          scanService.update(msg.keyword, CensysScanner.name, "Scanning").invoke()
//          this.search(msg.keyword).invoke()
//        }
//      }
//
//      log.info("Searching: " + msg.keyword)
//
//      Done
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
  private def scan(keyword: String, page: Int = 1)(implicit ec: ExecutionContext): Seq[CensysIpv4Result] = {
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