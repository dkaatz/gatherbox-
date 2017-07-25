package de.beuth.profile.scanner.impl

import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.Done
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.broker.TopicProducer
import com.lightbend.lagom.scaladsl.persistence.{EventStreamElement, PersistentEntityRegistry}
import de.beuth.ixquick.scanner.api.{IxquickScanUpdateEvent, IxquickScannerService, LinkedInUpdateEvent, XingUpdateEvent}
import de.beuth.profile.scanner.api.{ProfileScannerService, ProfileUrl}
import de.beuth.proxybrowser.api.{ProxyBrowserService, ProxyServer, RndProxyServer}
import de.beuth.utils.{ProfileLink, UserAgentList}

import scala.concurrent.duration._
import de.beuth.scan.api.ScanService
import de.beuth.scanner.commons.{ScanFailedEvent, ScanFinishedEvent, ScanStartedEvent, ScanStatusEvent}
import net.ruippeixotog.scalascraper.browser.{Browser, JsoupBrowser}
import net.ruippeixotog.scalascraper.dsl.DSL._
import net.ruippeixotog.scalascraper.dsl.DSL.Extract._
import org.openqa.selenium._
import org.openqa.selenium.firefox.{FirefoxBinary, FirefoxDriver}
import org.openqa.selenium.remote.{CapabilityType, DesiredCapabilities}
import org.openqa.selenium.support.ui.{FluentWait, Wait}
import play.api.libs.json.Json

import scala.collection.JavaConversions
import scala.collection.generic.CanBuildFrom
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.ws.{WSClient, WSResponse}

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import com.google.common.base.{ Function => GFunction }


/**
  * Implementation of [[de.beuth.profile.scanner.api.ProfileScannerService]]
  *
  * @param registry Injected persistent entity [[ProfileScannerEntity]]
  * @param system Injected actor system
  * @param wsClient Injected WebService Client
  * @param scanService Injected ScanService [[ScanService]]
  * @param proxyService Injected ScanService [[ProxyBrowserService]]
  * @param ec Implicitly injected execution context
  * @param mat Implicitly injected materiealizer
  */
class ProfileScannerImpl(registry: PersistentEntityRegistry,
                         system: ActorSystem,
                         wsClient: WSClient,
                         scanService: ScanService,
                         ixquickScannerService: IxquickScannerService,
                         proxyService: ProxyBrowserService)
                        (implicit ec: ExecutionContext, mat: Materializer)
  extends ProfileScannerService {

  private final val log: Logger = LoggerFactory.getLogger(classOf[ProfileScannerImpl])

  ixquickScannerService.statusTopic().subscribe.atLeastOnce(
    Flow[ScanStatusEvent].mapAsync(1) {
      case e: ScanStartedEvent => refFor(e.keyword).ask(StartScan(Instant.now()))
      case e: ScanFinishedEvent => refFor(e.keyword).ask(CompleteLinkCollection())
      case _ => Future.successful(Done)
    }
  )

  ixquickScannerService.updateTopic().subscribe.atLeastOnce(
    Flow[IxquickScanUpdateEvent].mapAsync(1) { event =>
      for {
        toScan <- refFor(event.keyword).ask(AddLinks(Instant.now(), event.data))
        // scan all profiles using a parallelized sequence to run them in parallel and wait for all results to finish
        scans <- allSuccessful(toScan.map {
          url: String =>
            //based on the event type we use a different scan service call
            event match {
              case e: LinkedInUpdateEvent => scanLinkedinProfile(event.keyword).invoke(ProfileUrl(url))
              case e: XingUpdateEvent => scanXingProfile(event.keyword).invoke(ProfileUrl(url))
             }
        })
        //we just return done
        done <- Future.successful(Done)
      } yield done
    }
  )


  def scanXingProfile(keyword: String) = ServiceCall { url: ProfileUrl => {
      for {
        proxy <- proxyService.getAvailableProxy().invoke()
        response <- proxiedGet(url.url, proxy)
        profile <- processXingProfile(url.url, response, keyword)
        free <- proxyService.free().invoke(proxy)
        done <- {
          //@todo do propper logging
          log.info(Json.toJson(profile).toString())
          refFor(keyword).ask(UpdateProfile(Instant.now(), profile))
        }
      } yield done
    }
  }

  /**
    * Scans Linkedin Profile and stores it in the persitent entity
    * @param keyword
    * @return
    */
  override def scanLinkedinProfile(keyword: String): ServiceCall[ProfileUrl, Done] = ServiceCall { url: ProfileUrl => {
      log.info("Scanning Linkedin Profile...")
      for {
        proxyServer <- proxyService.getAvailableProxy().invoke()
        profile <- fetchLinkedinProfile(url.url, proxyServer)
        updated <- refFor(keyword).ask(UpdateProfile(Instant.now(), profile))
      } yield updated
    }
  }

  /**
    * Creates the selenium webdriver fetches the page and invokes the profile page processing when the topcard is visiable
    *
    * @param url resource location of profile
    * @param proxyServer server used to fetch the profile
    *
    * @return
    */
  private def fetchLinkedinProfile(url: String, proxyServer: ProxyServer): Future[Profile] = {
    val proxy = new Proxy()
    proxy.setHttpProxy(proxyServer.toString).setSslProxy(proxyServer.toString)
    val cap = new DesiredCapabilities()
    cap.setCapability(CapabilityType.PROXY, proxy)
    System.setProperty("webdriver.gecko.driver", "/opt/geckodriver")
    val driver = new FirefoxDriver(cap)
    val wait: Wait[WebDriver] = new FluentWait[WebDriver](driver)
      .withTimeout(5, TimeUnit.SECONDS)
      .pollingEvery(2, TimeUnit.SECONDS)
      .ignoring(classOf[NoSuchElementException])

    driver.get(s"https://translate.google.de/translate?hl=de&sl=en&u=$url&prev=search")
    processLinkedinProfile(Future {
      //try to get "TopCard" with timeout and polling configured above
      wait.until[WebElement](toGoogleJavaFunction[WebDriver, WebElement](
        //Switching to I-Frame context of Linkedin Page
        (driver: WebDriver) => driver.switchTo().frame(0).findElement(By.id("topcard"))
      )
      )
    }, driver, url)
  }

  /**
    * Extracts content out of linkedin profile page
    *
    * @param we topcard web element
    * @param driver webdriver that loaded the profile page
    * @param profileUrl the url of the profile page
    * @return
    */
  private def processLinkedinProfile(we: Future[WebElement], driver: FirefoxDriver, profileUrl: String): Future[Profile] = {
      we flatMap {
        //when the topcard is ther we are sure that the other cards are also there if they exist so we extract them concurrently
        case topCard: WebElement => {
          val nameF = extractOrNone(topCard.findElement(By.id("name")).getText)
          val skillsF = extractOrNone(linkedinSkillsExtractor(driver))
          val expF = linkedinWorkExperienceExtractor(driver)

          for {
            name <- nameF
            skills <- skillsF
            exp <- expF
            profile <- Future {
              if (name.isEmpty) {
                throw ProfileScrapingException("Name not found.")
              }
              //to simplify name conversion we assume the "latin" rule to take the first firstname and last lastname
              val firstAndLastName = name.get.split("\\s+")
              Profile(
                scanned = true,
                //take first part of the name
                firstname = Some(firstAndLastName.head),
                // take last part of the name
                lastname = Some(firstAndLastName.last),
                updatedAt = Instant.now(),
                link = ProfileLink(profileUrl, ProfileLink.PROVIDER_LINKED_IN),
                skills = skills.getOrElse(List()).toSeq,
                exp = exp.toSeq
              )
            }
          } yield profile
        }
      } recoverWith {
        case e: Exception => {
          log.info(e.toString)
          log.info(e.getStackTraceString)
          driver.quit()
          throw e
        }
      }
  }

  /**
    * Shortcut for getting the actual persistentce entity by keyword
    *
    * @param keyword keyword identifier for entity
    * @return reference of persistence entity
    */
  private def refFor(keyword: String) = registry.refFor[ProfileScannerEntity](keyword)

  /**
    * Converts scala lambda function to com.google.common.base.Function for compability with selenium library
    *
    * @return
    */
  implicit def toGoogleJavaFunction[U, V](f:Function1[U,V]): GFunction[U, V] = new GFunction[U, V] {
    override def apply(t: U): V = f(t)
  }

  /**
    * Searches for the element with the id #skills and extracts all skill texts within it
    *
    * @param driver driver that loaded the page
    * @return
    */
  private def linkedinSkillsExtractor(driver: WebDriver) =
    JavaConversions.asScalaBuffer(driver.findElement(By.id("skills")).findElements(By.className("skill"))).toList map {
      case skill: WebElement => {
        skill.findElement(By.className("wrap")).getAttribute("textContent")
      }
    } filterNot (_.startsWith("See"))


  /**
    * Searches for the element with the id #experience and iterates over the .position class to extract the work experience
    * informations
    *
    * @param driver driver that loaded the page
    * @return
    */
  private def linkedinWorkExperienceExtractor(driver: WebDriver): Future[List[JobExperience]] =
    allSuccessful(JavaConversions.asScalaBuffer(driver.findElement(By.id("experience")).findElements(By.className("position"))).toList map {
      case position: WebElement => {
        val titleF = extractOrNone(position.findElement(By.className("item-title")).findElement(By.className("google-src-text")).getAttribute("textContent"))
        //extract subinformations concurrently
        val isCurrentF = extractOrNone(position.getAttribute("data-section").startsWith("current"))
        val fromToF = extractOrNone(position.findElements(By.className("date-range")))
        val companyF = extractOrNone(position.findElement(By.className("item-subtitle")).findElement(By.className("google-src-text")).getAttribute("textContent"))
        val descriptionF = extractOrNone((JavaConversions.asScalaBuffer(
              position.findElement(By.className("description")).findElements(By.className("google-src-text"))
            ).toList map {
              case description: WebElement => description.getAttribute("textContent")
            }
          ).mkString(" "))


        //wait until all futures are finished and build JobExperience object
        for {
          title <- titleF
          isCurrent <- isCurrentF
          fromTo <- fromToF
          from <- Future.successful(
            if(fromTo.isEmpty)
              None
            else
              Some(fromTo.get.get(0).getAttribute("textContent"))
          )
          to <-  Future.successful(
            if(fromTo.isEmpty)
              None
            else
              Some(fromTo.get.get(1).getAttribute("textContent"))
          )
          company <- companyF
          description <- descriptionF
          jobExperience <- Future.successful(JobExperience(
            title = title.get,
            isCurrent = isCurrent,
            from = from,
            to = to,
            company = company,
            description = description
          ))
        } yield jobExperience
      }
    }).recoverWith {
      case e: Exception => Future.successful(List())
    }

  /**
    * Fold left the collection of future's and wait for the previous future to finish and return it or fallback to the
    * current result if the future fails
    *
    * e.g.   List[Future[Int]] -- becomes --> Future[List[Int]]
    *        where the result only contains sucessfull resolved futures
    *
    * @param in - SequenceLike of Future of Objects to wait for
    * @param cbf - implicit can build from
    * @tparam A - ObjectLike Type
    * @tparam M - SequenceLike Type
    * @return
    */
  def allSuccessful[A, M[X] <: TraversableOnce[X]](in: M[Future[A]])
                                                  (implicit cbf: CanBuildFrom[M[Future[A]], A, M[A]]): Future[M[A]] = {
    in.foldLeft(Future.successful(cbf(in))) {
      (current, left) ⇒ (for (r ← current; a ← left) yield r += a) fallbackTo current
    } map (_.result())
  }

  /**
    * Wrapping extraction task in Future and returning Option of E
    *
    * @param extractor extractor function
    * @tparam E expected return type of extractor function
    * @return
    */
  private def extractOrNone[E](extractor: => E): Future[Option[E]] = {
    Future {
      Some(extractor)
    }.recover {
      case _ :Exception => None
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
      .withHeaders(
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
    * @param response http response of request
    * @return future of a Profile
    */
  private def processXingProfile(url: String, response: WSResponse, keyword: String): Future[Profile] = {
    if(!response.status.equals(200)) {
      throw ProfileScrapingException("Unable to fetch profile. Unexpected Response code: " + response.status)
    }
    val browser = JsoupBrowser().parseString(response.body);
    val namePositionCompany = browser >> element("head > meta[property='og:title']") >> attr("content") split(" - ")
    val name: Option[String] = try {
      Some(namePositionCompany(0))
    } catch {
      case e: Exception => throw ProfileScrapingException("name not found")
    }

    val jobtitle: Option[String] = try {
      Some(namePositionCompany(1))
    } catch {
      case e: ArrayIndexOutOfBoundsException => None
    }

    val company: Option[String] = try {
       Some(namePositionCompany(2))
    } catch {
      case e: ArrayIndexOutOfBoundsException => None
    }

    val haves = browser >> elementList("div.Haves ul span") map {
      have => have >> allText
    }
    val workExperience = browser >> elementList(".WorkExperience-jobInfo")
    val experienceList: List[JobExperience] = workExperience.map {
      exp => JobExperience(
        title = exp >> text(".WorkExperience-jobTitle"),
        company= exp >?> text("div:nth-child(3)"),
        from = exp >?> text(".WorkExperience-dateRange"),
        to = exp >?> text(".WorkExperience-dateRange"),
        description = None,
        isCurrent = None
      )
    }

    val firstnameLastname = name.get.split(" ", 2)

    Future.successful(Profile(
      scanned = true,
      firstname = Some(firstnameLastname(0)),
      lastname = Some(firstnameLastname(1)),
      updatedAt = Instant.now(),
      link = ProfileLink(url, ProfileLink.PROVIDER_XING),
      skills = haves.toSeq,
      exp = experienceList.toSeq
    ))
  }

  /**
    * Message Brocking
    */
  override def statusTopic(): Topic[ScanStatusEvent] =
    TopicProducer.singleStreamWithOffset {
      fromOffset =>
        registry.eventStream(ProfileScannerEvent.Tag , fromOffset)
          .filter {
            _.event match {
                //@todo add scanFailed
              case x@(_: ScanStarted | _: ScanFinished) => true
              case _ => false
            }
          }.map(ev => (convertStatusEvent(ev.entityId, ev), ev.offset))
    }

  private def convertStatusEvent(keyword: String, scanEvent: EventStreamElement[ProfileScannerEvent]): ScanStatusEvent = {
    scanEvent.event match {
      /**
        * We drop the urls here since they are not of particular interest for other services
        */
      case ScanStarted(timestamp) => ScanStartedEvent(keyword, timestamp)
      case ScanFinished() => ScanFinishedEvent(keyword, Instant.now())
      //@todo add ScanFailed
      //case ScanFailed(timestamp, errorMsg) => ScanFailedEvent(keyword, timestamp, errorMsg)
    }
  }
}
case class ProfileScrapingException(message: String) extends Exception