package de.beuth.scan.impl
import java.time.{Instant, LocalDateTime}

import akka.Done
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.persistence.{PersistentEntityRegistry}
import de.beuh.databreach.api.DataBreachService
import de.beuth.censys.scanner.api.CensysScannerService
import de.beuth.ixquick.scanner.api.IxquickScannerService
import de.beuth.linkedin.scanner.api.LinkedinScannerService
import de.beuth.scan.api._
import de.beuth.scanner.commons._
import de.beuth.xing.scanner.api.XingScannerService
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}

/**
  * Implementation of [[ScanService]]
  *
  */
class ScanServiceImpl(registry: PersistentEntityRegistry,
                      censysScannerService: CensysScannerService,
                      ixquickScannerService: IxquickScannerService,
                      linkedinScannerService: LinkedinScannerService,
                      xingScannerService: XingScannerService,
                      dataBreachService: DataBreachService
                     )(implicit ec: ExecutionContext, mat: Materializer)
  extends ScanService {

  private final val log: Logger = LoggerFactory.getLogger(classOf[ScanServiceImpl])

  /**
    * Below the subscription of the status topics of all relevant scanners
    */
  ixquickScannerService.statusTopic().subscribe.atLeastOnce(
    scanStatusEventHandler(IxquickScannerService.NAME)
  )

  linkedinScannerService.statusTopic().subscribe.atLeastOnce(
    scanStatusEventHandler(LinkedinScannerService.NAME)
  )

  censysScannerService.statusTopic().subscribe.atLeastOnce(
    scanStatusEventHandler(CensysScannerService.NAME)
  )

  xingScannerService.statusTopic().subscribe.atLeastOnce(
    scanStatusEventHandler(XingScannerService.NAME)
  )

  dataBreachService.statusTopic().subscribe.atLeastOnce(
    scanStatusEventHandler(DataBreachService.NAME)
  )

  /**
    * Handles the Message Flow of ScanStatusEvents
    * @param name Name of event source (scanner)
    * @return
    */
  private def scanStatusEventHandler(name: String) = {
    Flow[ScanStatusEvent].mapAsync(1) {
      case ev: ScanStartedEvent => {
        log.info(s"Event: ScanStartedEvent - Scanner: $name - Keyword: ${ev.keyword}")
        refFor(ev.keyword).ask(StartScanner(name, ev.timestamp))
      }
      case ev: ScanFinishedEvent => {
        log.info(s"Event: ScanFinishedEvent - Scanner: $name - Keyword: ${ev.keyword}")
        refFor(ev.keyword).ask(FinishScanner(name))
      }
      case other => Future.successful(Done)
        //@todo gracefully handle failed scans without just finishing them
        refFor(other.keyword).ask(FinishScanner(name))
    }
  }

  def startScan(keyword: String) = ServiceCall { _ =>
    refFor(keyword).ask(StartScan(Instant.now()))
  }

  def getScanStatus(keyword:String) = ServiceCall { _ =>
    refFor(keyword).ask(GetScan) map (scan => ScanStatus(keyword = keyword, startedat = scan.startedat, scanner = scan.scanner))
  }

  //shorthand
  private def refFor(keyword: String) = registry.refFor[ScanEntity](keyword)

  //default status behavior
  override def statusTopic() = statusTopicImpl(registry)
}

/**
  * This singleton object contains a initalization list of scanners expected to run
  */
object Scanners {
  def scanners = Seq[ScannerStatus](
    ScannerStatus(CensysScannerService.NAME, None, false),
    ScannerStatus(IxquickScannerService.NAME, None, false),
    ScannerStatus(LinkedinScannerService.NAME, None, false),
    ScannerStatus(XingScannerService.NAME, None, false),
    ScannerStatus(DataBreachService.NAME, None, false)
  )
}
