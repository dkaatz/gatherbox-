package de.beuth.ixquick.scanner.impl

import java.io.{File, InputStream}

import akka.actor.{ActorSystem, Scheduler}
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.persistence.{EventStreamElement, PersistentEntityRegistry}
import de.beuth.utils.UserAgentList
import de.beuth.ixquick.scanner.api.{IxquickScanUpdateEvent, IxquickScannerService, LinkedInUpdateEvent, XingUpdateEvent}
import org.slf4j.{Logger, LoggerFactory}
import net.ruippeixotog.scalascraper.browser.{Browser, JsoupBrowser}
import net.ruippeixotog.scalascraper.dsl.DSL._
import net.ruippeixotog.scalascraper.dsl.DSL.Extract._
import net.ruippeixotog.scalascraper.dsl.DSL.Parse._
import org.jsoup.HttpStatusException
import net.ruippeixotog.scalascraper.scraper.ContentExtractors.attr
import play.api.libs.json.{Format, Json}
import play.api.libs.ws._

import scala.concurrent.duration._
import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import de.beuth.proxybrowser.api.{ProxyBrowserService, ProxyServer}
import de.beuth.scan.api.ScanService
import java.time.Instant

import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.broker.TopicProducer
import de.beuth.scanner.commons._
import de.beuth.proxybrowser.api.RndProxyServer

/**
  * Created by David on 13.06.17.
  */
class IxquickScannerImpl(registry: PersistentEntityRegistry, system: ActorSystem, wsClient: WSClient, scanService: ScanService, proxyBrowserService: ProxyBrowserService)(implicit ec: ExecutionContext, mat: Materializer)
  extends IxquickScannerService {

  private final val log: Logger = LoggerFactory.getLogger(classOf[IxquickScannerImpl])

//  scanService.statusTopic().subscribe.atLeastOnce(
//    Flow[ScanStatusEvent].mapAsync(1) {
//      case ev: ScanStartedEvent => {
//        log.info("ScanStarted Event received. Starting Ixquick scans")
//        val linkedInFuture = this.scanLinkedin(ev.keyword).invoke()
//        val xingFuture = this.scanXing(ev.keyword).invoke()
//        (for {
//          linkedin <- linkedInFuture
//          xing <- xingFuture
//          finish <- refFor(ev.keyword).ask(FinishScan(Instant.now()))
//        } yield finish).recoverWith {
//          case e: Exception => refFor(ev.keyword).ask(ScanFailure(Instant.now(), e.getMessage))
//        }
//      }
//      //ignore other events
//      case _ => Future.successful(Done)
//    }
//  )

  def scanLinkedin(keyword: String) = ServiceCall { _ => {
      log.info(s"Scanning Linkedin Profiles with keyword: $keyword")
      val linkedIn1 = processKeyword(keyword, "de.linkedin.com/in", doUpdateLinkedIn)
      val linkedIn2 = processKeyword(keyword, "www.linkedin.com/in", doUpdateLinkedIn)
      for {
        f1 <- linkedIn1
        f2 <- linkedIn2
        result <- Future.successful(Done)
      } yield result
    }
  }

  def scanXing(keyword: String) = ServiceCall { _ => {
      log.info(s"Scanning Xing Profiles with keyword: $keyword")
      processKeyword(keyword, "www.xing.com/profile", doUpdateXing)
    }
  }




  private def doUpdateLinkedIn(keyword: String, profiles: Seq[String]): Future[Done] = refFor(keyword).ask(UpdateXing(profiles))
  private def doUpdateXing(keyword: String, profiles: Seq[String]) = refFor(keyword).ask(UpdateXing(profiles))
  private def refFor(keyword: String) = registry.refFor[IxquickScannerEntity](keyword)


  private def processKeyword(keyword: String, site: String, command: (String, Seq[String]) => Future[Done]): Future[Done] = {
    val proxyFuture = proxyBrowserService.getAvailableProxy().invoke()
    (for {
      proxy <- proxyFuture
      cleanLinks <- proxiedRecursiveRequestChain(IxquickQuery(query=s"site:$site $keyword"), proxy = proxy).map {
        links =>  links.filter(_.startsWith(s"https://$site")).distinct
      }
      proxyFreed <- proxyBrowserService.free().invoke(proxy)
      result <- command(keyword, cleanLinks)
    } yield result).recoverWith {
        case e: java.net.ConnectException => processKeywordWithNextProxy(keyword, site, command, proxyFuture)
        case e: HttpStatusException => processKeywordWithNextProxy(keyword, site, command, proxyFuture)
    }
  }

  private def processKeywordWithNextProxy(keyword: String,
                                          site: String,
                                          command: (String, Seq[String]) => Future[Done],
                                          proxyFuture: Future[ProxyServer]) =
    for {
      failedProxy <- proxyFuture
      reported <- proxyBrowserService.report().invoke(failedProxy)
      result <- processKeyword(keyword, site, command)
    } yield result

  private def proxiedRecursiveRequestChain(query: IxquickQuery,
                                           proxy: ProxyServer,
                                           resultSet: List[String] = List[String](),
                                           ixquickserver: String="https://www.ixquick.com/do/search"): Future[List[String]] =
    for {
        r1 <- proxiedPostWithRetry(url = ixquickserver, query = query, proxy = proxy)
        r2 <- if(r1._3.isEmpty || r1._3.diff(resultSet).isEmpty)
                Future.successful(resultSet ++ r1._3)
             else
                proxiedRecursiveRequestChain(
                  query = r1._1.copy(startAt = (r1._1.startAt.toInt + 10).toString),
                  resultSet = resultSet ++ r1._3,
                  ixquickserver = r1._2, proxy = proxy
                )
      } yield r2

  protected def proxiedPostWithRetry(url: String, query: IxquickQuery, retry: Int = 0, proxy: ProxyServer): Future[(IxquickQuery, String, List[String])] = {
    proxiedPost(url, query, proxy).map{
      case wsResponse:WSResponse if wsResponse.status.equals(200) => {
        val browser = JsoupBrowser().parseString(wsResponse.body);
        val linkList = (browser >> extractor("div.result h3 a", attrs("href"), seq(asIs[String]))).toList
        val pnform = browser >> element("#pnform")
        val qid = pnform >>  element("input[name=qid]") >> attr("value")
        val ppg = pnform >?> element("input[name=ppg]") >> attr("value")
        val cpg = pnform >?> element("input[name=cpg]") >> attr("value")
        val nj = pnform >?> element("input[name=nj]") >> attr("value")
        val ixquickserver = browser >> attr("action")("#pnform")

        log.info("[Ixqucikform] qid: "
          + qid.toString
          + "server: " + ixquickserver
          + "nj: " + nj.toString
          + "cpg: " + cpg.toString
          + "ppg: " + ppg.toString
        )
        (query.copy(qid=qid, ppg=ppg.getOrElse(""), cpg=cpg.getOrElse(""), nj=nj.getOrElse("")), ixquickserver, linkList)
      }
      case wsResponse:WSResponse => throw new HttpStatusException("Received unexpected status code", wsResponse.status, url)
    }.recoverWith {
      //proxy not reachable
      case e: java.net.ConnectException => throw e
      //allow retry
      case e: Throwable if retry < 8 => proxiedPostWithRetry(url, query, retry+1, proxy)
    }
  }

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
  override def statusTopic(): Topic[ScanStatusEvent] =
    TopicProducer.singleStreamWithOffset {
      fromOffset =>
        registry.eventStream(IxquickScannerEvent.Tag , fromOffset)
          .filter {
              _.event match {
                case x@(_: ScanStarted | _: ScanFinished | _: ScanFailed) => true
                case _ => false
              }
          }.map(ev => (convertStatusEvent(ev.entityId, ev), ev.offset))
    }

  private def convertStatusEvent(keyword: String, scanEvent: EventStreamElement[IxquickScannerEvent]): ScanStatusEvent = {
    scanEvent.event match {
      case ScanStarted(timestamp) => ScanStartedEvent(keyword, timestamp)
      case ScanFinished(timestamp) => ScanFinishedEvent(keyword, timestamp)
      case ScanFailed(timestamp, errorMsg) => ScanFailedEvent(keyword, timestamp, errorMsg)
    }
  }

  override def updateTopic(): Topic[IxquickScanUpdateEvent] =
    TopicProducer.singleStreamWithOffset {
      fromOffset =>
        registry.eventStream(IxquickScannerEvent.Tag , fromOffset)
          .filter {
            _.event match {
              case x@(_: LinkedinUpdated | _: XingUpdated) => true
              case _ => false
            }
          }
          .map(ev => (convertUpdateEvent(ev.entityId, ev), ev.offset))
    }

  private def convertUpdateEvent(keyword: String, scanEvent: EventStreamElement[IxquickScannerEvent]): IxquickScanUpdateEvent = {
    scanEvent.event match {
      case LinkedinUpdated(profiles) => LinkedInUpdateEvent(keyword, profiles)
      case XingUpdated(profiles) => XingUpdateEvent(keyword, profiles)
    }
  }
}

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