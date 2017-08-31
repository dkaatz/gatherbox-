package de.beuth.scanner.commons

import java.time.Instant

import akka.Done
import akka.actor.ActorRef
import akka.stream.scaladsl.Flow
import com.lightbend.lagom.scaladsl.persistence.{PersistentEntityRef, PersistentEntityRegistry}
import de.beuth.utils.WorkPullingPattern.Epic
import scala.collection.immutable.Seq

import scala.concurrent.{ExecutionContext, Future}

/**
  * Generalized behavior for the profile scanners who are using the work pulling pattern
  */
trait WorkPullingScanner {

  /**
    * This values have to be defined by subclasses
    */
  val repository: ProfileRepository
  val registry: PersistentEntityRegistry
  val master: ActorRef

  /**
    * The status handler for incoming status events from ixquick
    */
  protected def statusHandler(implicit ec: ExecutionContext) = {
    Flow[ScanStatusEvent].mapAsync(1) {
      case e: ScanStartedEvent => {
        refFor(e.keyword).ask(StartScan(Instant.now()))
      }
      case e: ScanFinishedEvent => {
        refFor(e.keyword).ask(CompleteLinkCollection())
      }
      case other => Future.successful(Done)
    }
  }

  /**
    * Update handler for incoming update events from ixuqick
    */
  protected def updateHandler(keyword: String, urls: Seq[String])(implicit ec: ExecutionContext)  = {
    for {
      toScan <- {
        refFor(keyword).ask(AddLinks(Instant.now(), urls))
      }
      worker <- scrapeProfiles(keyword, toScan)
    } yield Done
  }

  /**
    * General behavior on scraping profiles
    */
  protected def scrapeProfiles(keyword: String, urls: Seq[String])(implicit ec: ExecutionContext): Future[Done] = {
    for {
      //check if already in read side
      alreadyPresitedProfiles <- repository.getProfilesByUrls(urls)

      scrapeAndPersist <- Future {
        //if in read side persist them to the write side
        alreadyPresitedProfiles.foreach(profile => refFor(keyword).ask(UpdateProfile(Instant.now(), profile)))

        //filter the existing profiles out
        val profilesToScrape = urls.filterNot(url => alreadyPresitedProfiles.exists(p => p.url == url));

        //submit the remaining profiles to the master wich distributes the work
        master ! new Epic[ScrapingJob] { override val iterator = profilesToScrape.map {
          case urlToScan => ScrapingJob(keyword, urlToScan)
        }.iterator }
        Done
      }

    } yield Done
  }

  /**
    * Defines that scanners implementing this trait have to implment the refFor Method
    * this is needed to call reffor in this trait
    * @param keyword Keyword for PE reference
    * @return
    */
  protected def refFor(keyword: String): PersistentEntityRef[ProfileScannerEntity#Command]
}
