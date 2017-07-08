package de.beuth.profile.scanner.impl

import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.Done
import akka.actor.ActorSystem
import akka.stream.Materializer
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.persistence.{EventStreamElement, PersistentEntityRegistry}
import de.beuth.profile.scanner.api.{ProfileScannerService, ProfileUrl}
import de.beuth.proxybrowser.api.{ProxyBrowserService, ProxyServer, RndProxyServer}
import de.beuth.utils.UserAgentList

import scala.concurrent.duration._
import de.beuth.scan.api.ScanService
import de.beuth.scanner.commons.{ScanFailedEvent, ScanFinishedEvent, ScanStartedEvent, ScanStatusEvent}
import net.ruippeixotog.scalascraper.browser.{Browser, JsoupBrowser}
import net.ruippeixotog.scalascraper.dsl.DSL._
import net.ruippeixotog.scalascraper.dsl.DSL.Extract._
import net.ruippeixotog.scalascraper.dsl.DSL.Parse._
import org.openqa.selenium._
import org.openqa.selenium.firefox.{FirefoxBinary, FirefoxDriver}
import org.openqa.selenium.support.ui.{FluentWait, Wait}
import play.api.libs.json.Json

import scala.collection.JavaConversions
import scala.collection.generic.CanBuildFrom
import scala.concurrent.Await
import scala.reflect.io.{File, Path}
//import org.openqa.selenium.firefox.FirefoxDriver
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.ws.{WSClient, WSResponse}

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import com.google.common.base.{ Function => GFunction }


/**
  * Created by David on 27.06.17.
  */
class ProfileScannerImpl(registry: PersistentEntityRegistry, system: ActorSystem, wsClient: WSClient, scanService: ScanService, proxyService: ProxyBrowserService)(implicit ec: ExecutionContext, mat: Materializer)
  extends ProfileScannerService {

  private final val log: Logger = LoggerFactory.getLogger(classOf[ProfileScannerImpl])

  def scanProfile = ServiceCall { url: ProfileUrl => {
      for {
        proxy <- proxyService.getAvailableProxy().invoke()
        response <- proxiedGet(url.url, proxy)
        data <- processXingProfile(url.url, response)
        free <- proxyService.free().invoke(proxy)
        done <- Future.successful(Done)
      } yield done
    }
  }

  override def scanLinkedinProfile(): ServiceCall[ProfileUrl, Done] = ServiceCall { url: ProfileUrl => {
    System.setProperty("webdriver.gecko.driver", "/opt/geckodriver")
    val driver = new FirefoxDriver()
    val profileUrl = url.url
    val wait: Wait[WebDriver] = new FluentWait[WebDriver](driver)
      .withTimeout(5, TimeUnit.SECONDS)
      .pollingEvery(2, TimeUnit.SECONDS)
      .ignoring(classOf[NoSuchElementException])

    driver.get(s"https://translate.google.de/translate?hl=de&sl=en&u=$profileUrl&prev=search")
    Future {
      wait.until[WebElement](toGoogleJavaFunction[WebDriver, WebElement](
        //Switching to I-Frame context of Linkedin Page
        (driver: WebDriver) => driver.switchTo().frame(0).findElement(By.id("topcard"))
      )
      )
    } flatMap {
      case topCard: WebElement => {
        val nameF = extractOrNone(topCard.findElement(By.id("name")).getText)
        val jobTitleF = extractOrNone(topCard.findElement(By.className("headline")).getText)
        val localityF = extractOrNone(topCard.findElement(By.className("locality")).getAttribute("textContent"))
        val skillsF = extractOrNone(linkedinSkillsExtractor(driver))
        val expF = linkedinWorkExperienceExtractor(driver)

        for {
          name <- nameF
          jobTitle <- jobTitleF
          locality <- localityF
          skills <- skillsF
          exp <- expF
          profile <- Future {
            if (name.isEmpty) {
              throw ProfileScrapingException("Name not found.")
            }
            val firstAndLastName = name.get.split("\\s+", 2)
            firstAndLastName.foreach(w => log.info(w))
            Profile(
              firstname = firstAndLastName(0),
              lastname = firstAndLastName(1),
              updatedAt = Instant.now(),
//              extra = Map(
//                "fullName" -> name,
//                "jobTitle" -> jobTitle,
//                "locality" -> locality
//              ),
              link = LinkedinProfileLink(profileUrl),
              skills = skills.getOrElse(List()).toSeq,
              exp = exp.toSeq
            )
          }
          log <- {

            log.info(Json.toJson(profile).toString())
            driver.quit()
            Future.successful(Done)
          }
          store <- Future.successful(Done)
          //store <- refFor("rocket-internet-se").ask(ScanProfile(Instant.now(), profile))
        } yield store
       }
     } recoverWith {
      case e: Exception => {
        log.info(e.toString)
        log.info(e.getStackTraceString)
        driver.quit()
        Future.successful(Done)
        }
      }
    }
  }

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
      (fr, fa) ⇒ (for (r ← fr; a ← fa) yield r += a) fallbackTo fr
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

  private def processXingProfile(url: String, response: WSResponse): Future[Profile] = {
    if(!response.status.equals(200)) {
      throw ProfileScrapingException("Unable to fetch profile")
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
      firstname = firstnameLastname(0),
      lastname = firstnameLastname(1),
      updatedAt = Instant.now(),
//      extra = Map(
//        "fullName" -> name,
//        "jobTitle" -> jobtitle,
//        "company" -> company
//      ),
      link = XingProfileLink(url),
      skills = haves.toSeq,
      exp = experienceList.toSeq
    ))
  }




//@todo was intendet as profile scrape for linkedin
//  private def processXingProfile(url: String, response: WSResponse): Future[ProfileProfile] = {
//
//    if(response.status.equals(200)) {
//      val browser = JsoupBrowser().parseString(response.body);
//      val name = browser >> text("#name")
//      val test = browser >> elementList("#topcard table.extra-info a")
//      val links: Map[String, String] = test.map {
//        case e if e >> attr("data-tracking-control-name") contains("current") => "current" -> (e >> text)
//        case e if e >> attr("data-tracking-control-name") contains("past") => "past" -> (e >> text)
//        case e  => "education" -> (e >> text)
//      }(collection.breakOut)
//      log.info(links.toString())
//    } else {
//      log.info("Response failure: " + response.status + " - " + response.statusText + " Body: " + response.body)
//    }
//
//    Future.successful(ProfileProfile(url = url,timestamp = Instant.now(), raw = response.body, data = Map()))
//  }
//  /**
//    * Message Broking
//    */
//  override def statusTopic(): Topic[ScanStatusEvent] =
//    TopicProducer.singleStreamWithOffset {
//      fromOffset =>
//        registry.eventStream(IxquickScannerEvent.Tag , fromOffset)
////          .filter {
////            _.event match {
////              case x@(_: ScanStarted | _: ScanFinished | _: ScanFailed) => true
////              case _ => false
////            }
//          }.map(ev => (convertStatusEvent(ev.entityId, ev), ev.offset))
//    }
//
//  private def convertStatusEvent(keyword: String, scanEvent: EventStreamElement[IxquickScannerEvent]): ScanStatusEvent = {
//    ScanStartedEvent(keyword, timestamp)
//  }
}
case class ProfileScrapingException(msg: String) extends Exception