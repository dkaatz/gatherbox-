package de.beuth.linkedin.scanner.impl

import akka.Done
import akka.actor.ActorSystem
import akka.stream.Materializer
import com.lightbend.lagom.scaladsl.api.ServiceCall
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.broker.TopicProducer
import com.lightbend.lagom.scaladsl.persistence.{EventStreamElement, PersistentEntityRegistry}
import de.beuth.linkedin.scanner.api.LinkedinScannerService
import de.beuth.proxybrowser.api.ProxyBrowserService
import de.beuth.scan.api.ScanService
import de.beuth.scanner.commons.{ScanFailedEvent, ScanFinishedEvent, ScanStartedEvent, ScanStatusEvent}
import play.api.libs.ws.WSClient

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by David on 27.06.17.
  */
class LinkedinScannerImpl(registry: PersistentEntityRegistry, system: ActorSystem, wsClient: WSClient, scanService: ScanService, proxyBrowserService: ProxyBrowserService)(implicit ec: ExecutionContext, mat: Materializer)
  extends LinkedinScannerService {

  def scanProfile = ServiceCall { url: String => {
      Future.successful(Done)
    }
  }

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
