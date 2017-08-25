package de.beuth.scanner.commons

import java.time.Instant

import akka.Done
import akka.actor.ActorRef
import akka.stream.scaladsl.Flow
import com.lightbend.lagom.scaladsl.persistence.{PersistentEntityRef, PersistentEntityRegistry}
import de.beuth.utils.{ProfileLink, ScrapingJob}
import de.beuth.utils.WorkPullingPattern.Epic
import scala.collection.immutable.Seq

import scala.concurrent.{ExecutionContext, Future}

trait WorkPullingScanner {

  val repository: ProfileRepository
  val registry: PersistentEntityRegistry
  val master: ActorRef

  protected def statusHandler = {
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

  protected def updateHandler(keyword: String, urls: Seq[String])  = {
    for {
      toScan <- {
        refFor(keyword).ask(AddLinks(Instant.now(), urls))
      }
      worker <- scrapeProfiles(keyword, toScan)
    } yield Done
  }

  protected def scrapeProfiles(keyword: String, urls: Seq[String])(implicit ec: ExecutionContext): Future[Done] = {
    for {
      alreadyPresitedProfiles <- repository.getProfilesByUrls(urls)

      scrapeAndPersist <- Future {

        alreadyPresitedProfiles.foreach(profile => refFor(keyword).ask(UpdateProfile(Instant.now(), profile)))

        val profilesToScrape = urls.filterNot(url => alreadyPresitedProfiles.exists(p => p.url == url));

        master ! new Epic[ScrapingJob] { override val iterator = profilesToScrape.map {
          case urlToScan => ScrapingJob(keyword, urlToScan)
        }.iterator }
        Done
      }

    } yield Done
  }

  protected def refFor(keyword: String): PersistentEntityRef[ProfileScannerEntity#Command] = registry.refFor[ProfileScannerEntity](keyword)
}
