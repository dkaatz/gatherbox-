package de.beuth.linkedin.scanner.impl

import java.net.URL
import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.{Done, NotUsed}
import akka.actor.{ActorSystem, Props}
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.broker.TopicProducer
import com.lightbend.lagom.scaladsl.persistence.{EventStreamElement, PersistentEntityRegistry}
import de.beuth.ixquick.scanner.api.{IxquickScanUpdateEvent, IxquickScannerService}
import de.beuth.proxybrowser.api.ProxyBrowserService
import de.beuth.scan.api.ScanService
import de.beuth.scanner.commons._
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.ws.WSClient

import scala.concurrent.{Await, ExecutionContext, Future}
import de.beuth.utils.{Master, ProfileLink, ScrapingJob}
import de.beuth.utils.WorkPullingPattern.Epic
import de.beuth.linkedin.scanner.api.LinkedinScannerService
import play.api.Configuration

import scala.collection.immutable.Seq




/**
  * Implementation of [[de.beuth.linkedin.scanner.api.LinkedinScannerService]]
  *
  * @param registry Injected persistent entity [[LinkedinScannerEntity]]
  * @param repository Injected linkedin repository for read side queries [[LinkedinScannerEntity]]
  * @param system Injected actor system
  * @param wsClient Injected WebService Client
  * @param scanService Injected ScanService [[ScanService]]
  * @param proxyService Injected ScanService [[ProxyBrowserService]]
  * @param ec Implicitly injected execution context
  * @param mat Implicitly injected materiealizer
  */
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

  val master = system.actorOf(Props[Master[ScrapingJob]], "LinkedinCoordinator")

  for(i <- 0 until config.getOptional[Int]("xing-workers").orElse(Some(3)).get)
    system.actorOf(Props(new LinkedinScrapeWorker(master, registry, proxyService)))

  private final val log: Logger = LoggerFactory.getLogger(classOf[LinkedinScannerImpl])

  ixquickScannerService.statusTopic().subscribe.atLeastOnce(statusHandler)

  ixquickScannerService.updateTopic().subscribe.atLeastOnce(
    Flow[IxquickScanUpdateEvent].mapAsync(1) {
      case event: IxquickScanUpdateEvent if ProfileLink.deriveProvider(event.data.head) == ProfileLink.PROVIDER_LINKED_IN =>
        updateHandler(event.keyword, event.data)
      case other => Future.successful(Done)
    }
  )

  override def scrape(keyword: String): ServiceCall[String, Done] = ServiceCall { url: String => scrapeProfiles(keyword, Seq(url)) }

  /**
    * Message Broker
    */
  override def statusTopic() = statusTopicImpl(registry)
  override def updateTopic() = updateTopicImpl(registry)
}