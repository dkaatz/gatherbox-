package de.beuth.xing.scanner.impl

import java.time.Instant

import akka.Done
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
import de.beuth.utils.{Master, ProfileLink, ScrapingJob}
import de.beuth.utils.WorkPullingPattern.Epic
import de.beuth.xing.scanner.api.XingScannerService
import play.api.Configuration
import scala.collection.immutable.Seq

/**
  * Implementation of [[de.beuth.xing.scanner.api.XingScannerService]]
  *
  * @param registry Injected persistent entity [[XingScannerEntity]]
  * @param repository Injected Read Side Reposiotry
  * @param system Injected actor system
  * @param wsClient Injected WebService Client
  * @param scanService Injected ScanService [[ScanService]]
  * @param proxyService Injected ScanService [[ProxyBrowserService]]
  * @param ec Implicitly injected execution context
  * @param mat Implicitly injected materiealizer
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

  val master = system.actorOf(Props[Master[ScrapingJob]], "XingCoordinator")

  for(i <- 0 until config.getOptional[Int]("xing-workers").orElse(Some(6)).get)
    system.actorOf(Props(new XingScrapeWorker(master, registry, proxyService, wsClient)))

  private final val log: Logger = LoggerFactory.getLogger(classOf[XingScannerImpl])

  ixquickScannerService.statusTopic().subscribe.atLeastOnce(statusHandler)

  ixquickScannerService.updateTopic().subscribe.atLeastOnce(
    Flow[IxquickScanUpdateEvent].mapAsync(1) {
      case event: IxquickScanUpdateEvent
        if(ProfileLink.deriveProvider(event.data.head) == ProfileLink.PROVIDER_XING) => updateHandler(event.keyword, event.data)

      case other => Future.successful(Done)
    }
  )

  def scrape(keyword: String) = ServiceCall { url: String => {
     scrapeProfiles(keyword, Seq(url))
   }
  }
  /**
    * Message Brocking
    */
  override def statusTopic() = statusTopicImpl(registry)
  override def updateTopic() = updateTopicImpl(registry)
}