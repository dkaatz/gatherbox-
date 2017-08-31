package de.beuth.linkedin.scanner.impl

import akka.{Done}
import akka.actor.{ActorSystem, Props}
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.persistence.{EventStreamElement, PersistentEntityRegistry}
import de.beuth.ixquick.scanner.api.{IxquickScanUpdateEvent, IxquickScannerService}
import de.beuth.proxybrowser.api.ProxyBrowserService
import de.beuth.scan.api.ScanService
import de.beuth.scanner.commons._
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.ws.WSClient
import scala.concurrent.{ ExecutionContext, Future}
import de.beuth.utils.{Master, ProfileLink}
import de.beuth.linkedin.scanner.api.LinkedinScannerService
import play.api.Configuration
import scala.collection.immutable.Seq

class LinkedinScannerImpl(val registry: PersistentEntityRegistry,
                          val repository: LinkedinRepository,
                          system: ActorSystem,
                          wsClient: WSClient,
                          scanService: ScanService,
                          ixquickScannerService: IxquickScannerService,
                          proxyService: ProxyBrowserService,
                          config: Configuration
                         )
                         (implicit ec: ExecutionContext, mat: Materializer)
  extends LinkedinScannerService with WorkPullingScanner {

  //the master receiving the work
  val master = system.actorOf(Props[Master[ScrapingJob]], "LinkedinCoordinator")

  //start the workers for the master ( default value 2 )
  for(i <- 0 until config.getOptional[Int]("linkedin-workers").orElse(Some(2)).get)
    system.actorOf(Props(new LinkedinScrapeWorker(master, registry, proxyService)))

  //the logger for this class
  private final val log: Logger = LoggerFactory.getLogger(classOf[LinkedinScannerImpl])

  //subscibe to the status topic of ixquick
  ixquickScannerService.statusTopic().subscribe.atLeastOnce(statusHandler)


  ixquickScannerService.updateTopic().subscribe.atLeastOnce(
    Flow[IxquickScanUpdateEvent].mapAsync(1) {
      //the list have to be not empty and should contain linkedin links
      case event: IxquickScanUpdateEvent if !event.data.isEmpty &&  ProfileLink.deriveProvider(event.data.head) == ProfileLink.PROVIDER_LINKED_IN =>
        updateHandler(event.keyword, event.data)
      case other =>  {
        log.info(s"Received Xing Profile Links or empty list - discarding: ${other.data.toString()}.")
        Future.successful(Done)
      }
    }
  )

  //just calls the supertype method scrapProfiles
  override def scrape(keyword: String): ServiceCall[String, Done] = ServiceCall { url: String => scrapeProfiles(keyword, Seq(url)) }

  /**
    * Message Broker
    */
  override def statusTopic() = statusTopicImpl(registry)
  override def updateTopic() = updateTopicImpl(registry)

  override def refFor(keyword: String) = registry.refFor[LinkedinScannerEntity](keyword)
}