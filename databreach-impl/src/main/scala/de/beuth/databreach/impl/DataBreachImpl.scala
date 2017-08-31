package de.beuth.databreach.impl


import akka.Done
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.lightbend.lagom.scaladsl.persistence.{EventStreamElement, PersistentEntityRegistry}
import de.beuh.databreach.api.{DataBreachCombinedResults, DataBreachCombinedUpdatedEvent, DataBreachService, DataBreachToScanEvent}
import de.beuth.linkedin.scanner.api.LinkedinScannerService
import de.beuth.scanner.commons._
import de.beuth.xing.scanner.api.XingScannerService
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.broker.TopicProducer
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.InvalidCommandException
import de.beuth.databreach.scanner.api.{DataBreachResults, DataBreachScannerService, DataBreachUpdateEvent}
import org.slf4j.{Logger, LoggerFactory}
import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}

class DataBreachImpl(registry: PersistentEntityRegistry,
                     repository: DataBreachReposiotry,
                     linkedinService: LinkedinScannerService,
                     xingService: XingScannerService,
                     dataBreachScannerService: DataBreachScannerService
                    )(implicit ec: ExecutionContext, mat: Materializer)
extends DataBreachService {

  private final val log: Logger = LoggerFactory.getLogger(classOf[DataBreachImpl])

  /**
    * Subscription to topics of other services
    */
  linkedinService.statusTopic().subscribe.atLeastOnce(statusHandler(LinkedinScannerService.NAME)) //status topic to mark collections status
  xingService.statusTopic().subscribe.atLeastOnce(statusHandler(XingScannerService.NAME)) //status topic to mark collections status
  linkedinService.updateTopic().subscribe.atLeastOnce(updateHandler) //update topic to receive new profiles to scan for
  xingService.updateTopic().subscribe.atLeastOnce(updateHandler)//update topic to receive new profiles to scan for

  dataBreachScannerService.updateTopic().subscribe.atLeastOnce(Flow[DataBreachUpdateEvent].mapAsync(1) {
    case e: DataBreachUpdateEvent => refFor(e.keyword).ask(UpdateBreach(e.results))
    case other => Future.successful(Done)
  })

  /**
    * Handles what to do on incoming status events by the profile scanners
    *
    * @param serviceName the name of the scanner
    */
  private def statusHandler(serviceName: String) = Flow[ScanStatusEvent].mapAsync(1) {
    case e: ScanStartedEvent =>{
      //we recover gracefully since we can get 2 started events and the 2nd will throw an error
      refFor(e.keyword).ask(StartScan(e.timestamp)).recover {
        case exc: InvalidCommandException =>  Done
      }
      //anyways we mark that the service started the collection
      refFor(e.keyword).ask(StartCollection(serviceName))
    }
    //mark that the service finished the collection
    case e: ScanFinishedEvent => refFor(e.keyword).ask(CompleteCollection(serviceName))
    //ignore other events
    case other => Future.successful(Done)
  }

  /**
    * The update handler starts the scan for a profile
    */
  private def updateHandler =  Flow[ProfileUpdateEvent].mapAsync(1) {
    case e: ProfileUpdateEvent => {
      scanForProfile(e.keyword).invoke(e.profile)
    }
    case other => Future.successful(Done)
  }


  /**
    * QUeries the read side for exisiting results and performs based on that queries either a scan for both names,
    * for one name or for no name.
    *
    * @param keyword associated keyword
    * @return done
    */
  override def scanForProfile(keyword: String) = ServiceCall { profile: Profile =>
    if ( profile.lastname.isDefined && profile.firstname.isDefined ) {
      log.info(s"Received Profile to scan.... Additional Info: ${profile.toString}")
      for {
        //asking read side for firstname
        firstnameFromReadSide <- repository.findBreach(profile.firstname.get)
        //asking read side for lastname
        lastnameFromReadSide <- repository.findBreach(profile.lastname.get)

        //both names are already in the read side so we do not need to scan
        adToScanOrUpdate <-
        (firstnameFromReadSide, lastnameFromReadSide) match {
          case (f, l) if f.isDefined && l.isDefined =>{
            log.info(s"First and Lastname already in results")
            refFor(keyword).ask(
              UpdateBreachCombined(DataBreachCombinedResults(
                profile.url,
                profile.firstname.get,
                profile.lastname.get,
                true,
                f.get.results.getOrElse(Seq()) ++ l.get.results.getOrElse(Seq()))
              ))
          }
          // just the firstname is defined so we start a scan for the lastname
          case (f, l) if f.isDefined => {
            log.info(s"Lastname not in results")
            refFor(keyword).ask(AddLastnameToScan(profile.url, profile.firstname.get, profile.lastname.get))
            refFor(keyword).ask(UpdateBreach(DataBreachResults(profile.url, profile.firstname.get, f.get.results.getOrElse(Seq()))))
          }
          // just the lastname is defined so we start a scan for the firstname
          case (f, l) if l.isDefined => {
            log.info(s"Firstname not in results")
            refFor(keyword).ask(AddFirstnameToScan(profile.url, profile.firstname.get, profile.lastname.get))
            refFor(keyword).ask(UpdateBreach(DataBreachResults(profile.url, profile.lastname.get, l.get.results.getOrElse(Seq()))))
          }
          //both names need to be scanned
          case (f,l) => {
            log.info(s"Firstname and Lastname not in results")
            refFor(keyword).ask(AddFirstnameToScan(profile.url, profile.firstname.get, profile.lastname.get))
            refFor(keyword).ask(AddLastnameToScan(profile.url, profile.firstname.get, profile.lastname.get))
          }
        }
      } yield Done
    }
    else {
      log.debug(s"Unable to scan Breach since firstname and lastname need to be defined. Additional Info: ${profile.toString}")
      throw new InvalidCommandException("First and Lastname need to be defined")
    }
  }

  override def toScanTopic() = TopicProducer
    .singleStreamWithOffset {
      fromOffset =>
        registry.eventStream(ScannerEvent.Tag, fromOffset)
          .filter {
            _.event match {
              //we just want to handle this 2 events
              case x@(_: FirstnameToScanAdded | _: LastnameToScanAdded) => true
              case _ => false
            }
          }.map(ev => {
            log.info(s"Sending AddToScan Event to Message Broker")
            //emit the event to the Broker
            //@todo we do not need the match here we can just emit the event
            ev match {
              case EventStreamElement(keyword, FirstnameToScanAdded(url, firstname, lastname), offset) =>
                (DataBreachToScanEvent(ev.entityId, url, firstname), ev.offset)
              case EventStreamElement(keyword, LastnameToScanAdded(url, firstname, lastname), offset) =>
                (DataBreachToScanEvent(ev.entityId, url, lastname), ev.offset)
            }
          }
        )
    }

  override def updateTopic() = TopicProducer
    .singleStreamWithOffset {
      fromOffset =>
        registry.eventStream(ScannerEvent.Tag, fromOffset)
          .filter {
            _.event match {
              //we just want to handle this  event
              case _: BreachCombinedUpdated => true
              case _ => false
            }
          }.map(ev =>
          ev match {
            //@todo no need for matching here
            case EventStreamElement(keyword, BreachCombinedUpdated(breachResults), offset) =>
              (DataBreachCombinedUpdatedEvent(ev.entityId, breachResults), ev.offset)
          }
        )
    }

  //default status topic handling
  override def statusTopic() = statusTopicImpl(registry)

  //shorttag
  private def refFor(keyword: String) = registry.refFor[DataBreachEntity](keyword)
}