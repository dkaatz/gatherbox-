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
import de.beuth.censys.scanner.api.CensysScannerService
import de.beuth.ixquick.scanner.api.IxquickScannerService
import de.beuth.profile.scanner.api.ProfileScannerService
import de.beuth.scan.api._
import de.beuth.scanner.commons.{ScanFailedEvent, ScanFinishedEvent, ScanStartedEvent, ScanStatusEvent}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.immutable.Seq
import scala.util.{Failure, Success}
import scala.concurrent.{ExecutionContext, Future}

/**
  * Implementation of [[ScanService]]
  *
  * @param registry Inejcted persistent Entity [[ScanEntity]]
  * @param system Injected actor system
  * @param censysScannerService Injected [[CensysScannerService]]
  * @param ixquickScannerService Injected [[IxquickScannerService]]
  * @param profileScannerService Injected [[ProfileScannerService]]
  * @param ec Implicitly injected execution context used for callbacks
  * @param mat Implicitly injected materialzer used for materialzation of akka values (@todo think about removing)
  */
class ScanServiceImpl(registry: PersistentEntityRegistry,
                      system: ActorSystem,
                      censysScannerService: CensysScannerService,
                      ixquickScannerService: IxquickScannerService,
                      profileScannerService: ProfileScannerService
                     )(implicit ec: ExecutionContext, mat: Materializer)
  extends ScanService {

  private final val log: Logger = LoggerFactory.getLogger(classOf[ScanServiceImpl])

  ixquickScannerService.statusTopic().subscribe.atLeastOnce(
    scanStatusEventHandler(IxquickScannerService.NAME)
  )

  profileScannerService.statusTopic().subscribe.atLeastOnce(
    scanStatusEventHandler(ProfileScannerService.NAME)
  )

  censysScannerService.statusTopic().subscribe.atLeastOnce(
    scanStatusEventHandler(CensysScannerService.NAME)
  )

  /**
    * Handles the Message Flow of ScanStatusEvents
    * @param name Name of event source (scanner)
    * @return
    */
  private def scanStatusEventHandler(name: String) = Flow[ScanStatusEvent].mapAsync(1) {
    case ev: ScanStartedEvent => {
      log.info(s"Event: ScanStartedEvent - Scanner: $name - Keyword: ${ev.keyword}")
      refFor(ev.keyword).ask(StartScanner(name, ev.timestamp))
    }
    case ev: ScanFinishedEvent => {
      log.info(s"Event: ScanFinishedEvent - Scanner: $name - Keyword: ${ev.keyword}")
      refFor(ev.keyword).ask(FinishScanner(name))
    }
    //@todo handle scanner failed
  }

  def startScan(keyword: String) = ServiceCall { _ =>
    log.info(s"StartScan - keyword: $keyword")
    refFor(keyword).ask(StartScan(Instant.now()))
  }

  def getScanStatus(keyword:String) = ServiceCall { _ =>
    log.info(s"GetScanStatus - keyword: $keyword")
    refFor(keyword).ask(GetScan) map (scan => ScanStatus(keyword = keyword, startedAt = scan.startedAt, scanner = scan.scanner))
  }

  override def statusTopic(): Topic[ScanStatusEvent] =
    TopicProducer.singleStreamWithOffset {
      fromOffset =>
        registry.eventStream(ScanEvent.Tag , fromOffset)
          .map(ev => (convertEvent(ev.entityId, ev), ev.offset))
    }

  private def convertEvent(keyword: String, scanEvent: EventStreamElement[ScanEvent]): ScanStatusEvent = {
    scanEvent.event match {
      case ScanStarted(timestamp) => ScanStartedEvent(keyword, timestamp)
      case ScanFinished(timestamp) => ScanFinishedEvent(keyword, timestamp)
      case ScanFailed(timestamp, errorMsg) => ScanFailedEvent(keyword, timestamp, errorMsg)
    }
  }

  private def refFor(keyword: String) = registry.refFor[ScanEntity](keyword)
}

/**
  * This singleton object contains a initalization list of scanners expected to run
  */
object Scanners {
  def scanners = Seq[ScannerStatus](
    ScannerStatus(CensysScannerService.NAME, None, false),
    ScannerStatus(IxquickScannerService.NAME, None, false),
    ScannerStatus(ProfileScannerService.NAME, None, false)
  )
}
