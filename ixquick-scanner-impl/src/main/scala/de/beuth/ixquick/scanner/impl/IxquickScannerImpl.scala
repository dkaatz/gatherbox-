package de.beuth.ixquick.scanner.impl

import java.io.{File, InputStream}

import akka.actor.{ActorSystem, Scheduler}
import akka.stream.Materializer
import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.persistence.PersistentEntityRegistry
import de.beuth.censys.api.CensysService
import de.beuth.ixquick.scanner.api.IxquickScannerService
import org.slf4j.{Logger, LoggerFactory}
import net.ruippeixotog.scalascraper.browser.{Browser, JsoupBrowser}
import net.ruippeixotog.scalascraper.browser.JsoupBrowser._
import net.ruippeixotog.scalascraper.model._
import net.ruippeixotog.scalascraper.util._

import scala.collection.JavaConverters._
import net.ruippeixotog.scalascraper.dsl.DSL._
import net.ruippeixotog.scalascraper.dsl.DSL.Extract._
import net.ruippeixotog.scalascraper.dsl.DSL.Parse._
import net.ruippeixotog.scalascraper.util.using
import org.jsoup.Connection.Method.{GET, POST}
import org.jsoup.Connection.Response
import org.jsoup.{Connection, HttpStatusException, Jsoup}
import akka.pattern.after
import com.fasterxml.jackson.annotation.JsonFormat
import org.asynchttpclient.proxy.{ProxyServer => AHCProxyServer}
import play.api.http.Writeable
import play.api.libs.json.{Format, Json, Writes}
import play.api.libs.ws._
import play.api.mvc.Codec

import scala.concurrent.duration._
import scala.collection.immutable.Seq
import scala.collection.{TraversableOnce, mutable}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Random, Success, Try}

/**
  * Created by David on 13.06.17.
  */
class IxquickScannerImpl(registry: PersistentEntityRegistry, system: ActorSystem, wsClient: WSClient)(implicit ec: ExecutionContext, mat: Materializer)
  extends IxquickScannerService {

  private final val log: Logger = LoggerFactory.getLogger(classOf[IxquickScannerImpl])
  def scanLinkedin(keyword: String) = ServiceCall { _ => {
      log.info("calling scanLinkedin")
      for {
        r1 <- recursiveRequestChain(keyword).map {
          myList => {
            val cleanList = myList.filter(_.startsWith("https://de.linkedin.com/in")).distinct
            cleanList.foreach{ s: String => log.info(s)}
            cleanList
          }
        }
        r2 <- Future.successful(Done)
      } yield r2


//      val results = requestBulk(keyword)
//      results.map(list => {
//        list.foreach(url => log.info(url))
//        Done
//      })
//      synchronousRequestChain(keyword, "de.linkedin.com/in", 0).map{
//        list: Seq[String] => {
//          log.info("Got Results:" + list.length)
//          list.foreach(log.info(_))
//          Done
//        }
//      }
    }
  }

  private def recursiveRequestChain(keyword: String, startAt: Int = 0, resultSet: List[String] = List[String]()): Future[List[String]] =
    for {
        r1 <- proxiedPostWithRetry("https://www.ixquick.com/do/search", s"site:de.linkedin.com/in $keyword" , startAt)
        r2 <- if(r1.isEmpty || r1.filter(_.startsWith("https://de.linkedin.com/in")).diff(resultSet).isEmpty) Future.successful(resultSet ++ r1) else recursiveRequestChain(keyword, startAt+10, resultSet ++ r1)
      } yield r2


  //  protected[this] def processRequestRecursively(keyword: String, url: String, startAt: Int): Future[List[String]] = {
  //    for {
  //      res <- processRequest(keyword, url, startAt)
  //      combined <- res.toList +: processRequestRecursively(keyword:String, url, startAt+10)
  //    } yield combined
  //  }

  protected def proxiedPostWithRetry(url: String, query: String, startAt: Int, retry: Int = 0): Future[List[String]] = {
    proxiedPost(url,query, startAt).map{
      case wsResponse:WSResponse if wsResponse.status.equals(200) => {
          val tmp = (JsoupBrowser().parseString(wsResponse.body) >> extractor("div.result h3 a", attrs("href"), seq(asIs[String]))).toList
          if(tmp.isEmpty) log.info("No Links found.... Status: " + wsResponse.status + " Message: " + wsResponse.statusText)
          tmp
      }
      case wsResponse:WSResponse => {
        log.info("No Links found.... Status: " + wsResponse.status + " Message: " + wsResponse.statusText)
        throw new HttpStatusException("Received unexpected status code", wsResponse.status, url)
      }
    }.recoverWith {
      case e: Throwable if retry < 8 => {
        log.info(e.toString)
        proxiedPostWithRetry(url, query, startAt, retry+1)
      }
      case _ => Future.successful(List[String]()) //@todo add  error handling here
    }
  }

  private def proxiedPost(url: String, query: String, startAt: Int): Future[WSResponse] = {
    log.info(s"firing post $url starting at $startAt")
    wsClient.url(url)
      .withProxyServer(RndProxyServer())
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
//      .withBody(Json.toJson())
      .withRequestTimeout(30.seconds)
      .post(IxquickQuery(query=query, startAt = startAt.toString).asFormData)
  }


  /**
    * @TODO if we would consider to speed up the scan we could do concurrent calls ( this requires additional handling for errors and end of results)
    * @param keyword
    * @param startAt
    * @param concurrentCalls
    * @return
    */
  private def concurrentCalls(keyword: String, startAt: Int = 0, concurrentCalls: Int = 3): Future[Seq[String]] = {
    var futures = Seq[Future[WSResponse]]()
    val r = scala.util.Random
    for (i <- 0 to concurrentCalls) {
      futures = futures :+ proxiedPost("http://s" + (1 + r.nextInt(13 - 1)) + "-eu4.ixquick.com/do/search", query = s"site:de.linkedin.com/in $keyword", startAt + i * 10)
    }

    val listofSuccess = Future.sequence(futures.map(futureToFutureTry(_))).map(_.collect { case Success(x) => x })
    val listofFailure = Future.sequence(futures.map(futureToFutureTry(_))).map(_.collect { case Failure(x) => x })
    listofSuccess.map {
      res => {
        val tmp = res.flatMap(r => {
          val tmp2 = JsoupBrowser().parseString(r.body) >> extractor("div.result h3 a", attrs("href"), seq(asIs[String]))
          if(tmp2.isEmpty) log.info("No Links found.... Status: " + r.status + " Message: " + r.statusText)
          tmp2
        }).toSeq
        tmp
      }
    }
  }

  def futureToFutureTry[T](f: Future[T]): Future[Try[T]] =  f.map(Success(_)).recover({case x => {
    log.info(x.toString)
    Failure(x)
  }})


  private def requestBulk(keyword: String): Future[Seq[String]] =
    for{
      r1 <- proxiedPost("https://www.ixquick.de/do/search", query = s"site:de.linkedin.com/in $keyword", 0)
      r2 <- proxiedPost("https://www.ixquick.de/do/search", query = s"site:de.linkedin.com/in $keyword", 10)
      r3 <- proxiedPost("https://www.ixquick.de/do/search", query = s"site:de.linkedin.com/in $keyword", 20)
      r4 <- proxiedPost("https://www.ixquick.de/do/search", query = s"site:de.linkedin.com/in $keyword", 30)
      r5 <- proxiedPost("https://www.ixquick.de/do/search", query = s"site:de.linkedin.com/in $keyword", 40)
      p1 <- {
        Future.successful(Seq[WSResponse](r1, r2, r3, r4, r5).flatMap {
          r => JsoupBrowser().parseString(r.body) >> extractor("div.result h3 a", attrs("href"), seq(asIs[String]))
        })
      }
    } yield p1

//
//  private def synchronousRequestChain(keyword: String, url: String, startAt: Int, resultSet: Seq[String] = Seq[String]()): Future[Seq[String]] = {
//    for {
//      f1 <- processRequest(keyword, url, startAt)
//      next <- {
//        val f1converted = f1.toSeq
//        val newResultSet = resultSet ++ f1converted
//        log.info("Current count:" +  newResultSet.length + " prev: " + resultSet.length + " new: " + f1converted.length)
//        if(!f1converted.isEmpty && f1.toSet.intersect(resultSet.toSet).isEmpty) synchronousRequestChain(keyword, url, startAt+10, newResultSet) else {
//          f1converted.toSet.intersect(resultSet.toSet).foreach(x => log.info("intersection: " + x))
//          Future.successful(newResultSet)
//        }
//      }
//    } yield next
//  }



//  def scanLinkedin(keyword: String) = ServiceCall { _ => {
//    //      processRequest(keyword, "de.linkedin.com/in", 0).map {
//    //        case links: TraversableOnce[String] => {
//    //          val seq = links.toSeq
//    //
//    //          refFor(keyword).ask(UpdateLinkedIn(seq)).map {
//    //            case x: Done => x
//    //          }
//    //        }
//    //        Done
//    //      }
//    //    }
//
//      Done
//    }
//  }

//  protected[this] def processRequestRecursively(keyword: String, url: String, startAt: Int): Future[List[String]] = {
//    for {
//      res <- processRequest(keyword, url, startAt)
//      combined <- res.toList +: processRequestRecursively(keyword:String, url, startAt+10)
//    } yield combined
//  }

//
//  protected[this] def proccess50(keyword: String, url: String, startAt: Int): Future[List[String]] =
//    for {
//      res1 <- processRequest(keyword, url, startAt)
//      res2 <- processRequest(keyword, url, startAt+10)
//      res3 <- processRequest(keyword, url, startAt+20)
//      all <- Future.successful(res1.toList ++ res2.toList ++ res3.toList)
//    } yield all
//
//  protected[this] def processRequest(keyword: String, url: String, startAt: Int, depth: Int = 0): Future[TraversableOnce[String]] = {
//
//      val rndProxyIndex = 0 + Random.nextInt(ProxyList.proxies.length + 1)
//      val rndUserAgentIndex = 0 + Random.nextInt(UserAgentList.userAgents.length + 1)
//      ProxyUtils.setProxy(ProxyList.proxies(rndProxyIndex)._1, ProxyList.proxies(rndProxyIndexIndex)._2)
//      val browser = CustomJsoupBrowser(UserAgentList.userAgents(rndUserAgentIndex));
//      val query = "site:" + url + " " + keyword
//      var postData = Map(
//        "cmd" -> "process_search",
//        "language" -> "english",
//        "engine0" -> "v1all",
//        "query" -> query,
//        "hmb" -> "1",
//        "cpg" -> "0",
//        "startat" -> startAt.toString,
//        "t" -> "air",
//        "with_date" -> "y",
//        "nj" -> "0",
//        "abp" -> "-1",
//        "rl" -> "NONE"
//      )
//
//      log.info("Calling Search: " + postData.toString)
//
//      val searchResults = Future { browser.post("https://www.ixquick.de/do/search", postData) }
//      searchResults.map {
//        htmlresult => {
//          ProxyUtils.removeProxy()
//          val links = htmlresult >> extractor("div.result h3 a", attrs("href"), seq(asIs[String]))
//          //links.foreach(log.info(_))
//          links
//        }
//      }.recoverWith{
//        case _ if depth < 4 => processRequest(keyword, url, startAt, depth+1)
//        case _ => {
//          log.info("Failed for unkown reason...")
//          Future.successful(Seq[String]())
//        }
//      }
//  }


  def scanXing(keyword: String)= ServiceCall { _ => {
      //@dummy code
      Future.successful(Done)
    }
  }
}

case class IxquickQuery(
                  query: String,
                  startAt: String = "0",
                  cmd: String = "process_search",
                  language: String = "english",
                  engine0: String = "v1all",
                  hmb: String = "1",
                  cpg: String = "0",
                  t: String = "air",
                  with_date: String = "y",
                  nj: String = "0",
                  abd: String = "-1",
                  rl: String = "NONE") {
  def asFormData = {
    Map(
      "query" -> Seq(query),
      "startAt" -> Seq(startAt),
      "cmd" -> Seq(cmd),
      "language" -> Seq(language),
      "engine0" -> Seq(engine0),
      "hmb" -> Seq(hmb),
      "cpg" -> Seq(cpg),
      "t" -> Seq(t),
      //"with_date" -> Seq(with_date),
      "nj" -> Seq(nj),
      "abd" -> Seq(abd),
      "rl" -> Seq(rl)
    )
  }
}

object IxquickQuery {
  implicit val format: Format[IxquickQuery] = Json.format[IxquickQuery]
}


/**
  * Randomly selected proxy server
  *
  * @param host
  * @param port
  * @param protocol
  * @param principal
  * @param password
  * @param ntlmDomain
  * @param encoding
  * @param nonProxyHosts
  */
case class RndProxyServer(host: String,
                          port: Int,
                          protocol: Option[String],
                          principal: Option[String],
                          password: Option[String],
                          ntlmDomain: Option[String],
                          encoding: Option[String],
                          nonProxyHosts: Option[Seq[String]]
                         ) extends WSProxyServer

object RndProxyServer {

  private final val log: Logger = LoggerFactory.getLogger(classOf[RndProxyServer])
  private var next: Int = 0

  def apply(): RndProxyServer = {
    val rndProxy = proxies((1 + Random.nextInt(proxies.length-1)))
//    val rndProxy = proxies(next)
//    if(next >= (proxies.length-1))
//      next = 0;
//    else
//      next = next+1;
    log.info("Proxy Selected: " + rndProxy)
    RndProxyServer(rndProxy._1, rndProxy._2, Some("http"), None, None, None, None, None)
  }

  val proxies: List[(String, Int)] = List(
    ("82.165.72.56", 3128),
    ("88.99.149.188", 31288),
    ("144.76.32.78", 8080),
    ("89.40.124.65", 3128),
    ("5.189.189.196", 8080),
    ("5.189.189.196", 8888),
    ("89.40.116.171", 3128),
    ("213.185.81.135", 80),
    ("81.10.172.142", 3128),
    ("46.29.251.41", 3128),
    ("46.246.61.31", 3128),
    ("46.29.251.11", 3128),
    ("169.47.250.40", 3128),
    ("5.2.69.145", 1080),
    ("185.82.203.58", 1080),
    ("104.40.182.87", 3128),
    ("137.74.254.198", 3128),
    ("212.237.27.151", 3128),
    ("212.237.16.96", 8080),
    ("188.213.170.40", 8080),
    ("212.237.27.151", 80),
    ("212.237.38.216",8080)
  )
}

object UserAgentList {
  def getRnd(): String = userAgents(0 + Random.nextInt(userAgents.length))

  val userAgents: List[String] = List(
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36",
    "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36",
    "Mozilla/5.0 (Windows NT 6.3; WOW64; rv:53.0) Gecko/20100101 Firefox/53.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; WOW64; rv:53.0) Gecko/20100101 Firefox/53.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_5) AppleWebKit/603.2.4 (KHTML, like Gecko) Version/10.1.1 Safari/603.2.4",
    "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:53.0) Gecko/20100101 Firefox/53.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36",
    "Mozilla/5.0 (Windows NT 6.3; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36",
    "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:53.0) Gecko/20100101 Firefox/53.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_4) AppleWebKit/603.1.30 (KHTML, like Gecko) Version/10.1 Safari/603.1.30",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36",
    "Mozilla/5.0 (Windows NT 6.1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36",
    "Mozilla/5.0 (Windows NT 6.1; WOW64; Trident/7.0; rv:11.0) like Gecko",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.12; rv:53.0) Gecko/20100101 Firefox/53.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/57.0.2987.133 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:53.0) Gecko/20100101 Firefox/53.0",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.96 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/51.0.2704.79 Safari/537.36 Edge/14.14393",
    "Mozilla/5.0 (Windows NT 10.0; WOW64; Trident/7.0; rv:11.0) like Gecko",
    "Mozilla/5.0 (Windows NT 6.1; rv:53.0) Gecko/20100101 Firefox/53.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/57.0.2987.133 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.11; rv:53.0) Gecko/20100101 Firefox/53.0",
    "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.96 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.86 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64; rv:53.0) Gecko/20100101 Firefox/53.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_6) AppleWebKit/603.2.5 (KHTML, like Gecko) Version/10.1.1 Safari/603.2.5",
    "Mozilla/5.0 (Windows NT 6.1; Win64; x64; rv:53.0) Gecko/20100101 Firefox/53.0",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Ubuntu Chromium/58.0.3029.110 Chrome/58.0.3029.110 Safari/537.36",
    "Mozilla/5.0 (Windows NT 6.1; Trident/7.0; rv:11.0) like Gecko",
    "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/57.0.2987.133 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64; rv:45.0) Gecko/20100101 Firefox/45.0",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.86 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/52.0.2743.116 Safari/537.36 Edge/15.15063",
    "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/57.0.2987.133 Safari/537.36",
    "Mozilla/5.0 (Windows NT 6.3; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/56.0.2924.87 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36",
    "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; Trident/5.0;  Trident/5.0)",
    "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/57.0.2987.133 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.86 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.81 Safari/537.36",
    "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.0; Trident/5.0;  Trident/5.0)",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.81 Safari/537.36 OPR/45.0.2552.812",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36 OPR/45.0.2552.888",
    "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.86 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_5) AppleWebKit/603.2.5 (KHTML, like Gecko) Version/10.1.1 Safari/603.2.5",
    "Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_3) AppleWebKit/602.4.8 (KHTML, like Gecko) Version/10.0.3 Safari/602.4.8",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/56.0.2924.87 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/57.0.2987.133 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.96 Safari/537.36",
    "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:52.0) Gecko/20100101 Firefox/52.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/57.0.2987.133 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64; rv:52.0) Gecko/20100101 Firefox/52.0",
    "Mozilla/5.0 (Windows NT 5.1; rv:52.0) Gecko/20100101 Firefox/52.0",
    "Mozilla/5.0 (iPad; CPU OS 10_3_1 like Mac OS X) AppleWebKit/603.1.30 (KHTML, like Gecko) Version/10.0 Mobile/14E304 Safari/602.1",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_9_5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.10; rv:53.0) Gecko/20100101 Firefox/53.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36",
    "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:45.0) Gecko/20100101 Firefox/45.0",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/55.0.2883.87 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_6) AppleWebKit/603.1.30 (KHTML, like Gecko) Version/10.1 Safari/603.1.30",
    "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:52.0) Gecko/20100101 Firefox/52.0",
    "Mozilla/5.0 (Windows NT 6.2; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; WOW64; rv:52.0) Gecko/20100101 Firefox/52.0",
    "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:40.0) Gecko/20100101 Firefox/40.1",
    "Mozilla/5.0 (X11; Fedora; Linux x86_64; rv:53.0) Gecko/20100101 Firefox/53.0",
    "Mozilla/5.0 (iPad; CPU OS 10_3_2 like Mac OS X) AppleWebKit/603.2.4 (KHTML, like Gecko) Version/10.0 Mobile/14F89 Safari/602.1",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_4) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.96 Safari/537.36",
    "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.81 Safari/537.36"
  )
}