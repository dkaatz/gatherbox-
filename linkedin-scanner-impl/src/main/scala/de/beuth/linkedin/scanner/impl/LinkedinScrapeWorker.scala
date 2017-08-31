package de.beuth.linkedin.scanner.impl

import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import akka.Done
import akka.actor.ActorRef
import com.google.common.base.{Function => GFunction}
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import de.beuth.proxybrowser.api.{ProxyBrowserService, ProxyServer}
import de.beuth.scanner.commons._
import de.beuth.utils.{FutureHelper, Worker}
import org.openqa.selenium._
import org.openqa.selenium.support.ui.FluentWait
import org.slf4j.LoggerFactory
import scala.collection.JavaConversions
import scala.concurrent.Future


/**
  * Worker using the WorkPullingPattern who scans
  *
  * @param master The master controlling the message distribution
  * @param manifest
  */
class LinkedinScrapeWorker(override val master: ActorRef,
                           registry: PersistentEntityRegistry,
                           proxyService: ProxyBrowserService)
                          (implicit manifest: Manifest[ScrapingJob])
  extends Worker[ScrapingJob](master) {

  override protected final val log = LoggerFactory.getLogger(classOf[LinkedinScannerImpl])

  def doWork(work: ScrapingJob): Future[Done] = {
    log.info(s"${self.hashCode()} - Working on: ${work.payload}")
    //get a proxy
    val usedProxy = proxyService.getAvailableProxy().invoke()
    (for {
      proxy <- usedProxy
      //scrape the profile
      profile <- processLinkedinProfile(work.payload, proxy)

      //free the proxy
      free <- proxyService.free().invoke(proxy)

      //update the entity
      updated <- {
        refFor(work.keyword).ask(ScannedProfile(Instant.now(), profile))
      }
    } yield updated).recoverWith {
      case e: Exception =>
        for {
          proxy <- usedProxy
          //free the proxy
          free <- proxyService.free().invoke(proxy)
          //persist a failure
          failed <- refFor(work.keyword).ask(ProfileScanFailure(work.payload, Instant.now(), e.toString))
        } yield Done

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
  private def processLinkedinProfile(url: String, proxyServer: ProxyServer): Future[Profile] = {
    (for {
      //get the webdriver with the iframe focused
      driver <- getFocusedWebdriver(url, proxyServer)
      // try to scrape the profile
      profile <- {
        try {
          val wait = new FluentWait[WebDriver](driver).withTimeout(20, TimeUnit.SECONDS).pollingEvery(5, TimeUnit.SECONDS).ignoring(classOf[NoSuchElementException])

          scrapeLinkedinProfile(Future{wait.until[WebElement](toGoogleJavaFunction[WebDriver, WebElement](
            (d: WebDriver) => {
              log.info("Linkedin -  getting topcard")
              d.findElement(By.id("topcard"))
            }
          ))}, driver, url)
        } catch {
          case e: Exception =>
          {
            //close the driver
            closeWebDriver(driver)
            throw e
          }
        }
      }
    //close the driver
      close <- {
        Future {
          closeWebDriver(driver)
          Done
        }
      }
    } yield profile).recoverWith {
      //in this cases we try again
      case x@(_: IOException
              | _: org.openqa.selenium.TimeoutException) => retryWithNextProxy(url, proxyServer)
    }
  }

  /**
    * Retry with the next proxy server
    * @param url  url of the profile
    * @param currentProxy proxy used before
    * @return
    */
  private def retryWithNextProxy(url: String, currentProxy: ProxyServer):  Future[Profile] = {
    for {
      //report the old proxy
      report <- proxyService.report().invoke(currentProxy)
      //get a new one
      ps <- proxyService.getAvailableProxy().invoke()
      //retry
      results <- processLinkedinProfile(url, ps)
    } yield results
  }

  /**
    * @param url profile url to scrape
    * @param proxyServer proxy server used to scrape
    * @return
    */
  private def getFocusedWebdriver(url: String, proxyServer: ProxyServer): Future[WebDriver] =
    for {
      driver <- WebDriverFactory.getChromeRemoteDriver(Some(proxyServer))
      focusedDriver <- Future {

        try {
          driver.navigate().to(s"http://translate.google.de/translate?hl=de&sl=en&u=$url&prev=search")
          /**
            * Wait 1 Minute for the Iframe and check in intervals of 10 seconds if the iframe is there
            */
          new FluentWait[WebDriver](driver)
            .withTimeout(1, TimeUnit.MINUTES)
            .pollingEvery(10, TimeUnit.SECONDS)
            .ignoring(classOf[NoSuchFrameException])  //ignore exceptions of this type
            .until[WebDriver](toGoogleJavaFunction[WebDriver, WebDriver](
            //switch focus to iframe and return the webdriver
            (driver: WebDriver) => driver.switchTo().frame("c")
          ))
        } catch {
          case e: Exception => {
            closeWebDriver(driver)
            throw e
          }
        }
      }
    } yield focusedDriver

  /**
    * Extracts content out of linkedin profile page
    *
    * @param we topcard web element
    * @param driver webdriver that loaded the profile page
    * @param profileUrl the url of the profile page
    * @return
    */
  private def scrapeLinkedinProfile(we: Future[WebElement], driver: WebDriver, profileUrl: String): Future[Profile] = {
    we flatMap {
      //when the topcard is ther we are sure that the other cards are also there if they exist so we extract them concurrently
      case topCard: WebElement => {
        log.info("Linkedin -  getting name")
        val nameF = extractOrNone(topCard.findElement(By.id("name")).getText)
        log.info("Linkedin -  getting skills")
        val skillsF = extractOrNone(linkedinSkillsExtractor(driver))
        log.info("Linkedin -  getting workexperience")
        val expF = linkedinWorkExperienceExtractor(driver)

        nameF.onComplete {
          case _ => log.info("Linkedn - Name collected")
        }
        skillsF.onComplete {
          case _ => log.info("Linkedn - Skills collected")
        }

        expF.onComplete {
          case _ => log.info("Linkedn - JobExp collected")
        }

        for {
          name <- nameF
          skills <- skillsF
          exp <- expF
          profile <- Future {
            log.info("Linkedin -  combining results")
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
              updatedat = Instant.now(),
              url = profileUrl,
              skills = skills.getOrElse(List()).toSeq,
              exp = exp.toSeq
            )
          }
        } yield profile
      }
    }
  }

  /**
    * Searches for the element with the id #skills and extracts all skill texts within it
    *
    * @param driver driver that loaded the page
    * @return
    */
  private def linkedinSkillsExtractor(driver: WebDriver) = {
    JavaConversions.asScalaBuffer(driver.findElement(By.id("skills")).findElements(By.className("skill"))).toList map {
      case skill: WebElement => {
        log.info("Linkedin - scanning skill")
        skill.findElement(By.className("wrap")).getAttribute("textContent")
      }
    } filterNot (_.startsWith("See"))
  }


  /**
    * Searches for the element with the id #experience and iterates over the .position class to extract the work experience
    * informations
    *
    * @param driver driver that loaded the page
    * @return
    */
  private def linkedinWorkExperienceExtractor(driver: WebDriver): Future[List[JobExperience]] =
    try {
      FutureHelper().allSuccessful(JavaConversions.asScalaBuffer(driver.findElement(By.id("experience")).findElements(By.className("position"))).toList map {
        case position: WebElement => {
          log.info("Linedin - scanning position")

          //concurrently try to extract the data from the profiles
          val titleF = extractOrNone(position.findElement(By.className("item-title")).findElement(By.className("google-src-text")).getAttribute("textContent"))
          val isCurrentF = extractOrNone(position.getAttribute("data-section").startsWith("current"))
          val fromToF = extractOrNone(position.findElements(By.className("date-range")))
          val companyF = extractOrNone(position.findElement(By.className("item-subtitle")).findElement(By.className("google-src-text")).getAttribute("textContent"))

          //gets the description wich is in mulptiple html tags and joins them by using mkstring
          val descriptionF = extractOrNone((JavaConversions.asScalaBuffer(position.findElement(By.className("description")).findElements(By.className("google-src-text"))
          ).toList map {
            case description: WebElement => description.getAttribute("textContent")
          }).mkString(" "))
          //resolving the results of the futures and building the job exp object  out of them
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
              started = from,
              ended = to,
              company = company,
              description = description
            ))
          } yield jobExperience
        }
      }).recover {
        case e: NoSuchElementException => {
          // we return a empty list in this case because there are just no job experiences
          log.info("Linedin - a jobexp creation failed ")
          List()
        }
      }
    } catch {
      // we return a empty list in this case because there are just no job experiences
      case e: NoSuchElementException => Future.successful(List())
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
      case e : Throwable => {
        log.info(s"Failure while extracting element: $e")
        None
      }
    }
  }

  /**
    * Converts scala lambda function to com.google.common.base.Function for compability with selenium library
    *
    * @return
    */
  implicit def toGoogleJavaFunction[U, V](f:Function1[U,V]): GFunction[U, V] = new GFunction[U, V] {
    override def apply(t: U): V = f(t)
  }

  /**
    * Helper for closing the web browser
    */
  private def closeWebDriver(driver: WebDriver) = {
    driver.close()
    driver.quit()
  }

  private def refFor(keyword: String) = registry.refFor[LinkedinScannerEntity](keyword)
}