package de.beuth.xing.scanner.impl

import java.time.Instant

import akka.Done
import akka.actor.ActorRef
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import de.beuth.proxybrowser.api.{ProxyBrowserService, ProxyServer, RndProxyServer}
import de.beuth.scanner.commons._
import de.beuth.utils.{ProfileLink, UserAgentList, Worker}
import org.slf4j.LoggerFactory
import play.api.libs.json.Json
import play.api.libs.ws.{WSClient, WSResponse}

import scala.concurrent.duration._
import net.ruippeixotog.scalascraper.browser.JsoupBrowser
import net.ruippeixotog.scalascraper.dsl.DSL._
import net.ruippeixotog.scalascraper.dsl.DSL.Extract._
import play.api.libs.json.Json

import scala.collection.immutable.Seq
import scala.concurrent.Future


/**
  * Worker using the WorkPullingPattern who scans the xing profiles
  *
  * @param master The master controlling the message distribution
  * @param manifest
  */
class XingScrapeWorker(override val master: ActorRef,
                       registry: PersistentEntityRegistry,
                       proxyService: ProxyBrowserService,
                       wsClient: WSClient)
                          (implicit manifest: Manifest[ScrapingJob])
  extends Worker[ScrapingJob](master) {

  override protected final val log = LoggerFactory.getLogger(classOf[XingScrapeWorker])

  /**
    * Entry point for a scrape
    * @param work the work payload
    * @return returns a future of something
    */
  def doWork(work: ScrapingJob): Future[Done] = {
    log.info(s"${self.hashCode()} - Working on: ${work.payload}")
    //get a proxy here to be able to free it in the recover part
    val usedProxy = proxyService.getAvailableProxy().invoke()
    (for {
      proxy <- usedProxy
      //start the scrape
      profile <- processXingProfile(work.payload, proxy, work.keyword)
       //free the server
      free <- proxyService.free().invoke(proxy)
      //udpate the profile
      done <- {
        log.debug(s"Updating Xing Profile ${Json.toJson(profile).toString()}")
        refFor(work.keyword).ask(UpdateProfile(Instant.now(), profile))
      }
    } yield Done).recoverWith {
      case e: Exception =>
        for {
          proxy <- usedProxy
        //fre the server
          free <- proxyService.free().invoke(proxy)
        //persist that the scan failed
          failed <- refFor(work.keyword).ask(ProfileScanFailure(work.payload, Instant.now(), e.toString))
        } yield Done
    }
  }

  /**
    * Executes a get with a random user agent via a given ProxyServer
    *
    * @param url url to fetch
    * @param proxy proxy to use
    * @return http response
    */
  private def proxiedGet(url: String, proxy: ProxyServer): Future[WSResponse] = {
    val request = wsClient.url(url)
      .withProxyServer(RndProxyServer(proxy))
      .withHttpHeaders(
        ("User-Agent", UserAgentList.getRnd()),
        ("Accept-Language", "de-DE,de;q=0.8,en-US;q=0.6,en;q=0.4"),
        ("Cache-Control", "no-cache"),
        ("Pragma", "no-cache"),
        ("upgrade-insecure-requests", "1"),
        ("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"),
        ("Accept-Encoding", "gzip, deflate, br")
      )
      .withRequestTimeout(30.seconds)
    log.info(s"Request: $url")
    log.info(request.headers.toString())
    request.get()
  }

  /**
    * Extracts data from Xing Profile using the  ScalaScraper library (wrapper for Jsoup)
    *
    * @param url url to profile
    * @param proxy proxy server to use
    * @return future of a Profile
    */
  private def processXingProfile(url: String, proxy: ProxyServer, keyword: String): Future[Profile] =
    (for {
      response <- proxiedGet(url, proxy)
      profile <-  {
        if(!response.status.equals(200)) {
          throw ProfileScrapingException("Unable to fetch profile. Unexpected Response code: " + response.status)
        }
        //creting the jsoup brwoser from the html
        val browser = JsoupBrowser().parseString(response.body);

        //get name, position and company
        val namePositionCompany = browser >> element("head > meta[property='og:title']") >> attr("content") split(" - ")

        //try to geht the name
        val name: Option[String] = try {
          Some(namePositionCompany(0))
        } catch {
          case e: Exception => throw ProfileScrapingException("name not found")
        }

        //try to geht the jobtitle
        val jobtitle: Option[String] = try {
          Some(namePositionCompany(1))
        } catch {
          case e: ArrayIndexOutOfBoundsException => None
        }

        //try to geht the company
        val company: Option[String] = try {
          Some(namePositionCompany(2))
        } catch {
          case e: ArrayIndexOutOfBoundsException => None
        }

        //get all the haves
        val haves = browser >> elementList("div.Haves ul span") map {
          have => have >> allText
        }

        /**
          * Extract the job expierience
          */
        val workExperience = browser >> elementList(".WorkExperience-jobInfo")
        val experienceList: List[JobExperience] = workExperience.map {
          exp => JobExperience(
            title = exp >> text(".WorkExperience-jobTitle"),
            company= exp >?> text("div:nth-child(3)"),
            started = exp >?> text(".WorkExperience-dateRange"),
            ended = exp >?> text(".WorkExperience-dateRange"),
            description = None,
            isCurrent = None
          )
        }

        //split first and lastname
        val firstnameLastname = name.get.split(" ", 2)

        //return the results
        Future.successful(Profile(
          scanned = true,
          firstname = Some(firstnameLastname(0)),
          lastname = Some(firstnameLastname(1)),
          updatedat = Instant.now(),
          url = url,
          skills = haves.toSeq,
          exp = experienceList.toSeq))
      }
    } yield profile).recoverWith {
      //we try again with the next proxy when the error was IO or
      case x@(_: java.io.IOException | _: ProfileScrapingException) =>
        for {
          //report old proxy
          report <- proxyService.report().invoke(proxy)
          //get next one
          nextProxy <- proxyService.getAvailableProxy().invoke()
          // try again
          nextProfile <- processXingProfile(url, nextProxy, keyword)
        } yield nextProfile

      case e: Exception => {
        log.info(s"Failure while scraping xing profile: ${e.toString}")
        throw e
      }
    }


  private def refFor(keyword: String) = registry.refFor[XingScannerEntity](keyword)
}