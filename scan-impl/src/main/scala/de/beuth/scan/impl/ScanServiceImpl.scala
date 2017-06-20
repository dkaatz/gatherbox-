package de.beuth.scan.impl
import java.time.{Instant, LocalDateTime}

import akka.Done
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.broker.TopicProducer
import com.lightbend.lagom.scaladsl.persistence.{EventStreamElement, PersistentEntityRegistry}
import de.beuth.censys.api.{CensysQuery, CensysService}
import de.beuth.scan.api._
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
    log.info("Received call - scan -  " + keyword)
    refFor(keyword).ask(StartScan(Instant.now())).map {
      case scan: Scan => {
        ScanStatus(keyword, scan.startedAt, scan.isScanFinished(), scan.scanner.map(_.name))
      }
    }
  }

  def register(keyword: String, name: String) = ServiceCall { _ =>
    log.info("Received call - register " + name)
    refFor(keyword).ask(RegisterScanner(Instant.now(), name)).map {
      case scan: Scan => {
        log.info("Scann started")
        ScanStatus(keyword, scan.startedAt, scan.isScanFinished(), scan.scanner.map(_.name))
      }
    }
  }

  def unregister(keyword: String, name: String) = ServiceCall { _ =>
    log.info("Received call - unregister " + name)
    refFor(keyword).ask(UnregisterScanner(name)).map {
      case scan: Scan => {
        log.info("Scann started")
        ScanStatus(keyword, scan.startedAt, scan.isScanFinished(), scan.scanner.map(_.name))
      }
    }
  }

  def update(keyword: String, name: String, status: String) = ServiceCall { _ =>
    log.info("Received call - update " + name + " - Status " + status)

    refFor(keyword).ask(UpdateScanner(name, covertStatusString(status))).map {
      case scan: Scan => {
        if(scan.isScanFinished()) {
          refFor(keyword).ask(FinishScan(Instant.now()))
          log.info("Scan finished.... ")
        }
        ScanStatus(keyword, scan.startedAt, scan.isScanFinished(), scan.scanner.map(_.name))
      }
    }
  }

  private def covertStatusString(status: String): ScannerStatus.Status = status match {
    case "Unscanned" => ScannerStatus.Scanned
    case "Scanning" => ScannerStatus.Scanning
    case "Scanned" => ScannerStatus.Scanned
    case _ => ScannerStatus.Unscanned
  }

  override def scanStartedTopic(): Topic[ScanStartedMessage] =
    TopicProducer.singleStreamWithOffset {
      fromOffset =>
        registry.eventStream(ScanEvent.Tag , fromOffset)
          .map(ev => (convertEvent(ev), ev.offset))
    }

  private def convertEvent(scanEvent: EventStreamElement[ScanEvent]): ScanStartedMessage = {
    scanEvent.event match {
      case ScanStarted(keyword, timestamp) => ScanStartedMessage(keyword, timestamp)
    }
  }
//    refFor(keyword).ask(GetScan).map {
//      case Some(scan) => {
//        if(LocalDateTime.parse(scan.timestamp).isBefore(LocalDateTime.now().minusSeconds(2L))) {
//          log.info(s"Starting Scan...")
//          refFor(keyword).ask(StartScan(LocalDateTime.now().toString()))
//          censysService.searchIpv4.invoke(CensysQuery("\"" + keyword + "\"")) onComplete  {
//              case Success(results) => {
//                results.results.foreach(r => log.info(r.toString))
//                refFor(keyword).ask(FinishScan(ScanResult(LocalDateTime.now().toString(), Some(results.results))))
//              }
//              case Failure(ex) => {
//                log.info(s"Something went wrong... $ex")
//              }
//            }
//        }
//        log.info(s"Got Something...")
//        scan.result
//
//      }
//      case None => {
//        log.info(s"Got Nothing...")
//        refFor(keyword).ask(StartScan(LocalDateTime.now().toString()))
//        ScanResult(LocalDateTime.now().toString, None)
//      }
//    }
//  }

  private def refFor(keyword: String) = registry.refFor[ScanEntity](keyword)
}
