package de.beuth.ixquick.scanner.impl

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.Done
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.persistence.{EventStreamElement, PersistentEntityRegistry}
import de.beuth.utils.UserAgentList
import de.beuth.ixquick.scanner.api.{IxquickScanUpdateEvent, IxquickScannerService}
import org.slf4j.{Logger, LoggerFactory}
import net.ruippeixotog.scalascraper.browser.{Browser, JsoupBrowser}
import net.ruippeixotog.scalascraper.dsl.DSL._
import net.ruippeixotog.scalascraper.dsl.DSL.Extract._
import net.ruippeixotog.scalascraper.dsl.DSL.Parse._
import org.jsoup.HttpStatusException
import net.ruippeixotog.scalascraper.scraper.ContentExtractors.{attr, element}
import play.api.libs.json.{Format, Json}
import play.api.libs.ws._
import scala.concurrent.duration._
import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import de.beuth.proxybrowser.api.{ProxyBrowserService, ProxyServer}
import de.beuth.scan.api.ScanService
import java.time.Instant
import akka.persistence.query.Offset
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.broker.TopicProducer
import de.beuth.scanner.commons._
import de.beuth.proxybrowser.api.RndProxyServer


class IxquickScannerImpl(registry: PersistentEntityRegistry, system: ActorSystem, wsClient: WSClient, scanService: ScanService, proxyBrowserService: ProxyBrowserService)(implicit ec: ExecutionContext, mat: Materializer)
  extends IxquickScannerService {
  private final val log: Logger = LoggerFactory.getLogger(classOf[IxquickScannerImpl])

  /**
    * Subscription to the status topic of the scan service
    */
  scanService.statusTopic().subscribe.atLeastOnce(
    Flow[ScanStatusEvent].mapAsync(1) {

      case ev: ScanStartedEvent => {
        log.info(s"ScanStartedEvent received - Keywor: ${ev.keyword}")

        //start the 3 scans in parallel
        val startScan = refFor(ev.keyword).ask(StartScan(Instant.now()))
        val linkedInFuture = this.scanLinkedin(ev.keyword).invoke()
        val xingFuture = this.scanXing(ev.keyword).invoke()
        (for {
          scanStarted <- startScan
          linkedin <- linkedInFuture
          xing <- xingFuture
          //and wait for them to finish
          finish <- refFor(ev.keyword).ask(FinishScan(Instant.now()))
        } yield finish).recoverWith {
          case e: Exception => {
            log.info(s"Exception while scanning ixquick: ${e.toString}")
            refFor(ev.keyword).ask(ScanFailure(Instant.now(), e.getMessage))
          }
        }
      }
      case other => Future.successful(Done)
    }
  )

  /**
    * Starts scanning for german and english profiles in parallel
    *
    * @param keyword associated keyword
    * @return Done
    */
  def scanLinkedin(keyword: String) = ServiceCall { _ => {
      log.info(s"Scanning Linkedin Profiles with keyword: $keyword")
      val linkedIn1 = processKeyword(keyword, "de.linkedin.com/in")
      val linkedIn2 = processKeyword(keyword, "www.linkedin.com/in")
      for {
        f1 <- linkedIn1
        f2 <- linkedIn2
        result <- Future.successful(Done)
      } yield result
    }
  }

  /**
    * Starts scanning for xing profiles
    *
    * @param keyword associated keyword
    * @return Done
    */
  def scanXing(keyword: String) = ServiceCall { _ => {
      log.info(s"Scanning Xing Profiles with keyword: $keyword")
      processKeyword(keyword, "www.xing.com/profile")
    }
  }

  //shorthand
  private def refFor(keyword: String) = registry.refFor[IxquickScannerEntity](keyword)

  /**
    * Processing the keyword scan
    *
    * This method manages the scan flow,
    *
    * @param keyword associated keyword
    * @param site
    * @return
    */
  private def processKeyword(keyword: String, site: String): Future[Done] = {
    val proxyFuture = proxyBrowserService.getAvailableProxy().invoke()
    (for {
      proxy <- proxyFuture
      current <- refFor(keyword).ask(GetScan)
      (query: IxquickQuery, ixquickServer: String) <- {
        val defaultIxquickServer = "https://www.ixquick.com/do/search";
        val filtered = current.searches.filter(_.site == site)
        val iqq = s"site:$site $keyword"
        if (filtered.isEmpty)
          Future.successful((IxquickQuery(query = iqq), defaultIxquickServer))
        else
          for {
          //we do one fake request for the first site to procceed with last scanned site
            (iq: IxquickQuery, server: String, results: Seq[String]) <-
            proxiedPostWithRetry(url = defaultIxquickServer, query = IxquickQuery(query = iqq), proxy = proxy)

            q <- Future.successful(iq.copy(startAt = ((filtered(0).last_scanned_page + 1) * 10).toString))
          } yield (q, server)
      };
      processed <- proxiedRecursiveRequestChain(keyword = keyword, site = site, query = query, proxy = proxy, ixquickserver = ixquickServer)
      proxyFreed <- proxyBrowserService.free().invoke(proxy)
    } yield proxyFreed).recoverWith {
      case e: java.util.concurrent.TimeoutException => processKeywordWithNextProxy(keyword, site, proxyFuture)
      case e: java.io.IOException => processKeywordWithNextProxy(keyword, site, proxyFuture)
    }
  }

  /**
    * Tries to scan with the next proxy, first reporting the old one , then starting from beginning
    * @param keyword keyword to scan for
    * @param site site to scan for  eg. de.linkedin.com/in
    * @param proxyFuture the not working proxy
    */
  private def processKeywordWithNextProxy(keyword: String,
                                          site: String,
                                          proxyFuture: Future[ProxyServer]) =
    for {
      failedProxy <- proxyFuture
      reported <- proxyBrowserService.report().invoke(failedProxy)
      result <- processKeyword(keyword, site)
    } yield result

  private def proxiedRecursiveRequestChain(keyword: String,
                                           site: String,
                                           query: IxquickQuery,
                                           proxy: ProxyServer,
                                           ixquickserver: String = "https://www.ixquick.com/do/search"): Future[Done] =
    for {
      current <- refFor(keyword).ask(GetScan)
      (iq: IxquickQuery, server: String, results: Seq[String]) <- proxiedPostWithRetry(url = ixquickserver, query = query, proxy = proxy)
      store <- {
        log.info(s"Storing links: ${results.filter(_.startsWith(s"https://$site")).distinct.toString}")
        refFor(keyword).ask(UpdateSearch(site, iq.startAt.toInt / 10, results.filter(_.startsWith(s"https://$site")).distinct))
      }
      next <- {
        //fetching next algorithm
        val idx = current.searches.indexWhere(_.site == site)

        if (results.isEmpty || (idx != -1 && results.diff(current.searches(idx).links).isEmpty))
          Future.successful(Done)
        else
          for {
            sleep <- Future {
              Thread.sleep(10000L)
              Done
            }
            prrc <- proxiedRecursiveRequestChain(
              keyword,
              site,
              query = iq.copy(startAt = (query.startAt.toInt + 10).toString),
              ixquickserver = server, proxy = proxy
            )
          } yield prrc
      }
    } yield next

  protected def proxiedPostWithRetry(url: String, query: IxquickQuery, retry: Int = 0, proxy: ProxyServer): Future[(IxquickQuery, String, List[String])] = {
    log.info(s"Poxied get request - proxy: ${proxy.toFullString} - query: ${query.toString}")
    proxiedPost(url, query, proxy).map {
      case wsResponse: WSResponse if wsResponse.status.equals(200) => {
        //log.info(s"Response from Ixquick - status: ${wsResponse.status} - body: ${wsResponse.body}")
        val browser = JsoupBrowser().parseString(wsResponse.body);
        val linkList = (browser >> extractor("div.result h3 a", attrs("href"), seq(asIs[String]))).toList

        /**
          * Since Ixquick is changing the form names from time to time we try to fetch all forms and find the
          * needed meta data in them
          */
        val forms = browser >?> elementList("form")

        //captcha scenario, we got no forms at all so we got detected as a robot and we should report the proxy server
        if (!forms.isDefined) {
          throw PnFormNotFoundException(s"No forms found at all!! Something went wrong")
        }

        //try to get the qid from any of the forms
        val qids = (forms.get >?> element("input[name=qid]") >> attr("value")).filter(_.isDefined)
        val qid = qids.headOption.getOrElse(None)

        //try to get the ppg from any of the forms
        val ppgs = (forms.get >?> element("input[name=ppg]") >> attr("value")).filter(_.isDefined)
        val ppg = ppgs.headOption.getOrElse(None)

        //try to get the cpg from any of the forms
        val cpgs = (forms.get >?> element("input[name=cpg]") >> attr("value")).filter(_.isDefined)
        val cpg = cpgs.headOption.getOrElse(None)

        //try to get the nj from any of the forms
        val njs = (forms.get >?> element("input[name=nj]") >> attr("value")).filter(_.isDefined)
        val nj = cpgs.headOption.getOrElse(None)

        //try to get processing server from any of the forms
        val ixquickservers = browser >?> attr("action")("form")
        val ixquickserver = ixquickservers.filter(_.contains("eu")).headOption

        //just log for information
        log.info("[Ixqucikform] qid: "
          + qid.toString
          + "server: " + ixquickserver
          + "nj: " + nj.toString
          + "cpg: " + cpg.toString
          + "ppg: " + ppg.toString
        )
        //return the updated query  and the links
        (query.copy(qid = qid.getOrElse(""), ppg = ppg.getOrElse(""), cpg = cpg.getOrElse(""), nj = nj.getOrElse("")), ixquickserver.getOrElse("https://www.ixquick.com/do/search"), linkList)
      }
      //we got an unexpexted response code
      case wsResponse: WSResponse => {
        log.info(s"Received unexpected status code: ${wsResponse.status} with body: ${wsResponse.body}")
        throw new HttpStatusException("Received unexpected status code", wsResponse.status, url)
      }
    }.recoverWith {
      //proxy not reachable
      case e: java.io.IOException => throw e

        //any other case we retry 3 times
      case e: Throwable if retry < 2 => {
        log.info(s"Exception while recurisve ixquick scan with retry $retry: ${e.toString}")
        proxiedPostWithRetry(url, query, retry + 1, proxy)
      }
    }
  }

  /**
    * This method performs a post request with the given ixquick query and the given proxy server to the given url
    * Therfore it uses a random  user agent header to get not detected as a robot
    *
    * @param url url to call ( ixquick server endpoint )
    * @param query the ixquick query
    * @param proxy the proxy to use
    * @return
    */
  private def proxiedPost(url: String, query: IxquickQuery, proxy: ProxyServer): Future[WSResponse] = {
    log.info(s"firing post $url starting at " + query.startAt + " - with qid:" + query.qid)
    wsClient.url(url)
      .withProxyServer(RndProxyServer(proxy))
      .withHeaders(
        ("User-Agent", UserAgentList.getRnd()),
        ("Accept-Language", "de-DE,de;q=0.8,en-US;q=0.6,en;q=0.4"),
        ("Cache-Control", "no-cache"),
        ("Content-Type", "application/x-www-form-urlencoded"),
        ("Origin", "https://www.ixquick.com"),
        ("Referer", "https://www.ixquick.com/do/asearch"),
        ("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"),
        ("Accept-Encoding", "gzip, deflate, br")
      )
      .withRequestTimeout(30.seconds)
      .post(query.asFormData)
  }


  /**
    * Message Broking
    */
  override def statusTopic() = statusTopicImpl(registry)

  override def updateTopic(): Topic[IxquickScanUpdateEvent] =
    TopicProducer.singleStreamWithOffset {
      fromOffset =>
        registry.eventStream(ScannerEvent.Tag, fromOffset).filter{
          _.event match {
            case _: ScannerUpdateEvent => true
            case _ => false
          }
        }.map(ev => convertUpdateEvent(ev))
    }

  //@todo no need for pattern match and extra method
  private def convertUpdateEvent(scanEvent: EventStreamElement[ScannerEvent]): (IxquickScanUpdateEvent, Offset) =
    scanEvent match {
      case EventStreamElement(keyword, SearchUpdated(site, page, links), offset) => (IxquickScanUpdateEvent(keyword, links), offset)
    }
}


/**
  * Meta data for the query send to ixquick to look like a human who was using the forms provided by ixquick
  */
case class IxquickQuery(
                  query: String,
                  qid: String = "",
                  startAt: String = "0",
                  cmd: String = "process_search",
                  language: String = "english",
                  engine0: String = "v1all",
                  hmb: String = "1",
                  rcount: String = "",
                  cpg: String = "",
                  ppg: String = "",
                  nj: String = "0",
                  t: String = "air",
                  with_date: String = "",
                  abd: String = "-1",
                  rl: String = "NONE",
                  cat: String = "web"
                       ) {
  //transforms to a map
  def asFormData = {
    Map(
      "query" -> Seq(query),
      "qid" -> Seq(qid),
      "startAt" -> Seq(startAt),
      "cmd" -> Seq(cmd),
      "language" -> Seq(language),
      "engine0" -> Seq(engine0),
      "hmb" -> Seq(hmb),
      "rcount" -> Seq(rcount),
      "ppg" -> Seq(ppg),
      "cpg" -> Seq(cpg),
      "t" -> Seq(t),
      "with_date" -> Seq(with_date),
      "nj" -> Seq(nj),
      "abd" -> Seq(abd),
      "rl" -> Seq(rl),
      "cat" -> Seq(cat)
    )
  }
}

object IxquickQuery {
  implicit val format: Format[IxquickQuery] = Json.format[IxquickQuery]
}

//no form found --> no meta data --> no way to scrape
case class PnFormNotFoundException(message: String) extends Exception(message)