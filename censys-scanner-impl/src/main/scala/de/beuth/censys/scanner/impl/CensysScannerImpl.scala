package de.beuth.censys.scanner.impl

import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.persistence.query.Offset
import akka.stream.scaladsl.Flow
import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.broker.TopicProducer
import com.lightbend.lagom.scaladsl.persistence.{EventStreamElement, PersistentEntityRegistry}
import de.beuth.censys.api.{CensysIpv4Result, CensysIpv4SearchResult, CensysQuery, CensysService}
import de.beuth.censys.scanner.api.CensysScannerService
import de.beuth.scan.api.ScanService
import de.beuth.scanner.commons.{ScanFailedEvent, ScanFinishedEvent, ScanStartedEvent, ScanStatusEvent}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.immutable.Seq
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.Duration

class CensysScannerImpl(registry: PersistentEntityRegistry, censysService: CensysService, scanService: ScanService)(implicit ec: ExecutionContext) extends CensysScannerService {
  import scala.concurrent.ExecutionContext.Implicits.global

  private final val log: Logger =
    LoggerFactory.getLogger(classOf[CensysScannerImpl])

    scanService.statusTopic().subscribe.atLeastOnce(
      Flow[ScanStatusEvent].mapAsync(1) {
        case ev: ScanStartedEvent => {
          log.info(s"ScanStartedEvent received - Keywor: ${ev.keyword}")
          search(ev.keyword).invoke()
        }
        case _ => Future.successful(Done)
      }
    )

  /**
    * Performs a full scan by keyword
    * @param keyword
    * @return
    */
  def search(keyword: String) = ServiceCall { _ => {
      for {
        scanStarted <- refFor(keyword).ask(StartScan(Instant.now()))
        scanResults <- scanIpv4(keyword)
        updated <- {
          log.info("----- Updating Censys Results ---- ")
          refFor(keyword).ask(UpdateScan(scanResults))
        }
        finished <-  {
          log.info("----- Censys Finished ----")
          refFor(keyword).ask(FinishScan(Instant.now()))
        }
      } yield finished
    }
  }

  /**
    * Recursivley fetches pages of ipv4 search results from censys and joins the results of all pages
    *
    * @param keyword
    * @param page
    * @return
    */
  private def scanIpv4(keyword: String, page: Int = 1): Future[Seq[CensysIpv4Result]] = {
    log.info(s"Scanning Ipv4 - Keyword: $keyword - Page: $page")
    for {
      ipv4Result: CensysIpv4SearchResult <- censysService.searchIpv4().invoke(CensysQuery(query = "\"" + keyword + "\"", page = page))
      nextPage: Seq[CensysIpv4Result] <- if(ipv4Result.metadata.pages >= ipv4Result.metadata.page) scanIpv4(keyword, page + 1) else Future.successful(Seq())
      combined: Seq[CensysIpv4Result] <- Future.successful(ipv4Result.results ++ nextPage)
    } yield combined
  }


  private def refFor(keyword: String) = registry.refFor[CensysScannerEntity](keyword)

  /**
    * Topics
    */
  override def statusTopic(): Topic[ScanStatusEvent] =
    TopicProducer.singleStreamWithOffset {
      fromOffset =>
        registry.eventStream(CensysScannerEvent.Tag , fromOffset)
          .filter(
            _.event match {
              case x@(_: ScanStarted | _: ScanFinished) => true
              case _ => false
            }
          )
          .mapAsync(1)(ev => {
            log.info(s"converting event ${ev.event.toString}")
            convertEvent(ev)
          })
    }

  private def convertEvent(scanEvent: EventStreamElement[CensysScannerEvent]): Future[(ScanStatusEvent, Offset)] = {
    scanEvent match {
      case EventStreamElement(keyword, ScanStarted(timestamp), offset) => Future.successful((ScanStartedEvent(keyword, timestamp), offset))
      case EventStreamElement(keyword, ScanFinished(timestamp), offset) => Future.successful((ScanFinishedEvent(keyword, timestamp), offset))
      //case ScanFailed(timestamp, errorMsg) => ScanFailedEvent(keyword, timestamp, errorMsg)
    }
  }

}