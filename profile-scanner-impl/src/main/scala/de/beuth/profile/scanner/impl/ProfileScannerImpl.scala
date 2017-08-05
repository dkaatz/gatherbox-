package de.beuth.profile.scanner.impl

import java.net.URL
import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.Done
import akka.actor.{ActorSystem, Props}
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.broker.TopicProducer
import com.lightbend.lagom.scaladsl.persistence.{EventStreamElement, PersistentEntityRegistry}
import de.beuth.ixquick.scanner.api.{IxquickScanUpdateEvent, IxquickScannerService}
import de.beuth.profile.scanner.api.{ProfileScannerService, ProfileUrl}
import de.beuth.proxybrowser.api.ProxyBrowserService
import de.beuth.scan.api.ScanService
import de.beuth.scanner.commons.{ScanFailedEvent, ScanFinishedEvent, ScanStartedEvent, ScanStatusEvent}
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.ws.WSClient

import scala.concurrent.{Await, ExecutionContext, Future}
import akkapatterns.Master
import akkapatterns.WorkPullingPattern.Epic
import de.beuth.utils.ProfileLink



//just a second
import java.io._

/**
  * Implementation of [[de.beuth.profile.scanner.api.ProfileScannerService]]
  *
  * @param registry Injected persistent entity [[ProfileScannerEntity]]
  * @param system Injected actor system
  * @param wsClient Injected WebService Client
  * @param scanService Injected ScanService [[ScanService]]
  * @param proxyService Injected ScanService [[ProxyBrowserService]]
  * @param ec Implicitly injected execution context
  * @param mat Implicitly injected materiealizer
  */
class ProfileScannerImpl(registry: PersistentEntityRegistry,
                         system: ActorSystem,
                         wsClient: WSClient,
                         scanService: ScanService,
                         ixquickScannerService: IxquickScannerService,
                         proxyService: ProxyBrowserService)
                        (implicit ec: ExecutionContext, mat: Materializer)
  extends ProfileScannerService {

  val linkedInMaster = system.actorOf(Props[Master[ScrapingJob]], "LinkedinCoordinator")
  val xingMaster = system.actorOf(Props[Master[ScrapingJob]], "XingCoordinator")

  //@todo read count of workers from directory
  //starting workers
  for(i <- 0 until 3)  system.actorOf(Props(new LinkedinScrapeWorker(linkedInMaster, registry, proxyService)))
  for(i <- 0 until 3)  system.actorOf(Props(new XingScrapeWorker(xingMaster, registry, proxyService, wsClient)))

  private final val log: Logger = LoggerFactory.getLogger(classOf[ProfileScannerImpl])

  ixquickScannerService.statusTopic().subscribe.atLeastOnce(
    Flow[ScanStatusEvent].mapAsync(1) {
      case e: ScanStartedEvent => {
        log.info("Received scanStartedEvent form Ixquick")
        refFor(e.keyword).ask(StartScan(Instant.now()))
      }
      case e: ScanFinishedEvent => {
        log.info("Received ScanFInishedEvent form Ixquick")
        refFor(e.keyword).ask(CompleteLinkCollection())
      }
      case other => Future.successful(Done)
    }
  )

  ixquickScannerService.updateTopic().subscribe.atLeastOnce(
    Flow[IxquickScanUpdateEvent].mapAsync(1) {
      case event: IxquickScanUpdateEvent => for {
        toScan <- {
          log.info("Received UpdateEvent form Ixquick")
          refFor(event.keyword).ask(AddLinks(Instant.now(), event.data))
        }
        worker <- {
          if(ProfileLink.deriveProvider(event.data.head) == ProfileLink.PROVIDER_LINKED_IN)
            //sinding the Scan Jobs to the Worker
            linkedInMaster ! new Epic[ScrapingJob] { override val iterator = toScan.map {
              case urlToScan => ScrapingJob(event.keyword, urlToScan)
            }.iterator }
          else
            xingMaster ! new Epic[ScrapingJob] { override val iterator = toScan.map {
              case urlToScan => ScrapingJob(event.keyword, urlToScan)
            }.iterator }

          Future.successful(Done)
        }
        //we just return done
        done <- Future.successful(Done)
      } yield done
      case other => Future.successful(Done)
    }
  )

  def scanXingProfile(keyword: String) = ServiceCall { url: ProfileUrl => {
    Future {
      xingMaster ! new Epic[ScrapingJob] { override val iterator = List(url.url).map {
        case urlToScan => ScrapingJob(keyword, urlToScan)
      }.iterator }
      Done
    }
    }
  }

  /**
    * Scans Linkedin Profile and stores it in the persitent entity
    * @param keyword
    * @return
    */
  override def scanLinkedinProfile(keyword: String): ServiceCall[ProfileUrl, Done] = ServiceCall { url: ProfileUrl => {
      Future {
        linkedInMaster ! new Epic[ScrapingJob] { override val iterator = List(url.url).map {
          case urlToScan => ScrapingJob(keyword, urlToScan)
        }.iterator }
        Done
      }
    }
  }

  /**
    * Shortcut for getting the actual persistentce entity by keyword
    *
    * @param keyword keyword identifier for entity
    * @return reference of persistence entity
    */
  private def refFor(keyword: String) = registry.refFor[ProfileScannerEntity](keyword)

  /**
    * Message Brocking
    */
  override def statusTopic(): Topic[ScanStatusEvent] =
    TopicProducer.singleStreamWithOffset {
      fromOffset =>
        registry.eventStream(ProfileScannerEvent.Tag , fromOffset)
          .filter {
            _.event match {
                //@todo add scanFailed
              case x@(_: ScanStarted | _: ScanFinished) => true
              case _ => false
            }
          }.map(ev => (convertStatusEvent(ev.entityId, ev), ev.offset))
    }

  private def convertStatusEvent(keyword: String, scanEvent: EventStreamElement[ProfileScannerEvent]): ScanStatusEvent = {
    scanEvent.event match {
      /**
        * We drop the urls here since they are not of particular interest for other services
        */
      case ScanStarted(timestamp) => ScanStartedEvent(keyword, timestamp)
      case ScanFinished() => ScanFinishedEvent(keyword, Instant.now())
      //@todo add ScanFailed
      //case ScanFailed(timestamp, errorMsg) => ScanFailedEvent(keyword, timestamp, errorMsg)
    }
  }
}

case class ScanQueueEntry(keyword: String, url: String) {

}

case class ProfileScrapingException(message: String) extends Exception(message)