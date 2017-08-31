package de.beuth.xing.scanner.impl

import java.time.Instant

import akka.{Done, NotUsed}
import akka.actor.{ActorSystem, Props}
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.broker.TopicProducer
import com.lightbend.lagom.scaladsl.persistence.{EventStreamElement, PersistentEntityRef, PersistentEntityRegistry}
import de.beuth.ixquick.scanner.api.{IxquickScanUpdateEvent, IxquickScannerService}
import de.beuth.proxybrowser.api.ProxyBrowserService
import de.beuth.scan.api.ScanService
import de.beuth.scanner.commons._
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.ws.WSClient

import scala.concurrent.{ExecutionContext, Future}
import de.beuth.utils.{Master, ProfileLink}
import de.beuth.utils.WorkPullingPattern.Epic
import de.beuth.xing.scanner.api.XingScannerService
import play.api.Configuration

import scala.collection.immutable.Seq

/**
  * Implementation of [[de.beuth.xing.scanner.api.XingScannerService]]
  *
  */
class XingScannerImpl(val registry: PersistentEntityRegistry,
                      val repository: XingRepository,
                      system: ActorSystem,
                      wsClient: WSClient,
                      scanService: ScanService,
                      ixquickScannerService: IxquickScannerService,
                      proxyService: ProxyBrowserService,
                      config: Configuration
                     )
                     (implicit ec: ExecutionContext, mat: Materializer)
  extends XingScannerService with WorkPullingScanner {

  //creating the master node
  val master = system.actorOf(Props[Master[ScrapingJob]], "XingCoordinator")

  //creating the configured amount of workers
  for(i <- 0 until config.getOptional[Int]("xing-workers").orElse(Some(4)).get)
    system.actorOf(Props(new XingScrapeWorker(master, registry, proxyService, wsClient)))

  private final val log: Logger = LoggerFactory.getLogger(classOf[XingScannerImpl])

  //the status topic
  ixquickScannerService.statusTopic().subscribe.atLeastOnce(statusHandler)

  //the
  ixquickScannerService.updateTopic().subscribe.atLeastOnce(
    Flow[IxquickScanUpdateEvent].mapAsync(1) {
      case event: IxquickScanUpdateEvent
        //we just handle the message when the list is not empty and contains xing urls
        if(!event.data.isEmpty && ProfileLink.deriveProvider(event.data.head) == ProfileLink.PROVIDER_XING) => updateHandler(event.keyword, event.data)

      case other => {
        log.info(s"Received Linkedin Profile Links or an empty list - discarding: ${other.data.toString()}.")
        Future.successful(Done)
      }
    }
  )

  //just call scrapeProfiles from the supertype
  def scrape(keyword: String) = ServiceCall { url: String => {
     scrapeProfiles(keyword, Seq(url))
   }
  }

  //used for debugging to see actual state of the entity
  override def getState(keyword: String): ServiceCall[NotUsed, ProfileScannerState] = ServiceCall { _ => refFor(keyword).ask(GetProfiles) }

  /**
    * Message Brocking
    */
  override def statusTopic() = statusTopicImpl(registry)
  override def updateTopic() = updateTopicImpl(registry)

  //shorthand
  override def refFor(keyword: String) = registry.refFor[XingScannerEntity](keyword)
}