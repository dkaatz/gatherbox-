package de.beuth.censys.scanner.impl

import java.time.Instant

import akka.stream.scaladsl.Flow
import akka.Done
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.broker.TopicProducer
import com.lightbend.lagom.scaladsl.persistence.{EventStreamElement, PersistentEntityRegistry}
import de.beuth.censys.api.{CensysIpv4SearchResult, CensysQuery, CensysService}
import de.beuth.censys.scanner.api.{CensysScanUpdateEvent, CensysScannerService}
import de.beuth.scan.api.ScanService
import de.beuth.scanner.commons._
import org.slf4j.{Logger, LoggerFactory}
import scala.concurrent.{ExecutionContext, Future}

class CensysScannerImpl(registry: PersistentEntityRegistry, censysService: CensysService, scanService: ScanService)(implicit ec: ExecutionContext) extends CensysScannerService {

  //the logger for this class
  private final val log: Logger =
    LoggerFactory.getLogger(classOf[CensysScannerImpl])

  /**
    * Subscriping to the status topic of the scan service to receive scan started events
    */
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
    *
    * @param keyword
    * @return
    */
  def search(keyword: String) = ServiceCall { _ => {
      for {
        //we persist the scan started event first
        scanStarted <- refFor(keyword).ask(StartScan(Instant.now()))

        //then we are scanning for results
        scanResults <- scanIpv4(keyword)

        //when the scan is finished we persist the scan finished event
        finished <-  {
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
  private def scanIpv4(keyword: String, page: Int = 1): Future[Done] = {
    log.info(s"Scanning Ipv4 - Keyword: $keyword - Page: $page")
    for {
      //get the ipv4 search results
      ipv4Result: CensysIpv4SearchResult <- censysService.searchIpv4().invoke(CensysQuery(query = "\"" + keyword + "\"", page = page))
      //update the entity
      update: Done <- refFor(keyword).ask(UpdateScan(ipv4Result.results))
      // fetch the next page if its not the last page already
      nextPage: Done <- if(ipv4Result.metadata.pages > ipv4Result.metadata.page) scanIpv4(keyword, page + 1) else Future.successful(Done)
    } yield nextPage
  }


  //shorthand for registry.refFor...
  private def refFor(keyword: String) = registry.refFor[CensysScannerEntity](keyword)


  /**
    * Message Broking
    */

  //uses default scan status topic provison
  override def statusTopic() = statusTopicImpl(registry)

  /**
    * Publishes the updated results to the message broker
    *
    * @return
    */
  override def updateTopic(): Topic[CensysScanUpdateEvent] =
    TopicProducer.singleStreamWithOffset {
      fromOffset =>
        registry.eventStream(ScannerEvent.Tag , fromOffset)
          //we first filter out all status events
          .filter(
            _.event match {
              case _: ScannerUpdateEvent => true
              case _ => false
            }
          )
          .mapAsync(1) {
            //if we receive the ScanUpdated vent we publish it to the update topic
            case EventStreamElement(keyword, ScanUpdated(ipv4), offset) => {
                log.info(s"Publishing: $keyword - ${ipv4.toString}")
                Future.successful((CensysScanUpdateEvent(keyword, ipv4), offset))
             }
            }
          }

}