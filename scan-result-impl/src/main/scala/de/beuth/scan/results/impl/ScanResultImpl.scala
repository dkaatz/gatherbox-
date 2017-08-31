package de.beuth.scan.results.impl


import akka.{Done, NotUsed}
import akka.stream.scaladsl.Flow
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import de.beuh.databreach.api.{DataBreachCombinedUpdatedEvent, DataBreachService}
import de.beuth.censys.scanner.api.{CensysScanUpdateEvent, CensysScannerService}
import de.beuth.linkedin.scanner.api.LinkedinScannerService
import de.beuth.scan.results.api.{CensysResult, ProfileResult, ScanResult, ScanResultService}
import de.beuth.scanner.commons.ProfileUpdateEvent
import de.beuth.xing.scanner.api.XingScannerService
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}

/**
  * The implementation of the Service
  */
class ScanResultImpl(registry: PersistentEntityRegistry,
                     censysScannerService: CensysScannerService,
                     linkedinScannerService: LinkedinScannerService,
                     xingScannerService: XingScannerService,
                     dataBreachService: DataBreachService
                     )(implicit ec: ExecutionContext)
  extends ScanResultService {

  /**
    * Handle update events from censys
    */
  censysScannerService.updateTopic().subscribe.atLeastOnce(
    Flow[CensysScanUpdateEvent].mapAsync(1) {
      case ev: CensysScanUpdateEvent => {
        log.info(s"Event: CensysScanUpdateEvent -: ${ev.keyword}")
        //convert each result and add it to the entity
        ev.ipv4.map(CensysResult(_)).foreach( censysResult =>
          refFor(ev.keyword).ask(AddCensys(censysResult))
        )
        Future.successful(Done)
      }
      case other => Future.successful(Done)
    }
  )

  /**
    * Handle update evnets from xing
    */
  linkedinScannerService.updateTopic().subscribe.atLeastOnce(
    Flow[ProfileUpdateEvent].mapAsync(1) {
      case ev: ProfileUpdateEvent => {
        log.info(s"Event: LinkedinProfileUpdateEvent -: ${ev.keyword}")
        //convert profile and add to entity
        refFor(ev.keyword).ask(AddLinkedin(ProfileResult(ev.profile)))
      }
      case other => Future.successful(Done)
    }
  )
  /**
    * Handle update evnets from xing
    */
  xingScannerService.updateTopic().subscribe.atLeastOnce(
    Flow[ProfileUpdateEvent].mapAsync(1) {
      case ev: ProfileUpdateEvent => {
        log.info(s"Event: XingProfileUpdateEvent -: ${ev.keyword}")
        //convert profile and add to entity
        refFor(ev.keyword).ask(AddXing(ProfileResult(ev.profile)))
      }
      case other => Future.successful(Done)
    }
  )

  /**
    * Handles updates from data breach service
    */
  dataBreachService.updateTopic().subscribe.atLeastOnce(
    Flow[DataBreachCombinedUpdatedEvent].mapAsync(1) {
      case ev: DataBreachCombinedUpdatedEvent => {
        log.info(s"Event: DataBreachUpdateEvent -: ${ev.keyword}")
        refFor(ev.keyword).ask(AddBreach(ev.results))
      }
      case other => Future.successful(Done)
    }
  )

  private final val log: Logger = LoggerFactory.getLogger(classOf[ScanResultImpl])

  override def getResults(keyword: String): ServiceCall[NotUsed, ScanResult] = ServiceCall { _ => {
      refFor(keyword: String).ask(GetScanResult)
    }
  }

  private def refFor(keyword: String) = registry.refFor[ScanResultEntity](keyword)
}
