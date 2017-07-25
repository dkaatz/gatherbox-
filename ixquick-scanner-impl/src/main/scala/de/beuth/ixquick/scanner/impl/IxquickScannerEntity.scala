package de.beuth.ixquick.scanner.impl

import java.time.Instant

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventTag, PersistentEntity}
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.ReplyType
import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}
import de.beuth.utils.JsonFormats.singletonFormat
import play.api.libs.json.{Format, Json}

import scala.collection.immutable.Seq

/**
  * Created by David on 08.06.17.
  */
class IxquickScannerEntity extends PersistentEntity {
  override type Command = IxquickScannerCommand
  override type Event = IxquickScannerEvent
  override type State = Scan

  override def initialState: Scan = Scan(None, Seq())

  override def behavior: Behavior = {
    Actions().onCommand[StartScan, Done] {
      case (StartScan(timestmap), ctx, state) =>
        ctx.thenPersist(
          ScanStarted(timestmap)
        ) {
          _ => ctx.reply(Done)
        }
    }.onCommand[UpdateSearch, Done] {
      case (UpdateSearch(site, page, links), ctx, state) =>
        ctx.thenPersist(
          SearchUpdated(site, page, links)
        ) {
          _ => ctx.reply(Done)
        }
    }.onCommand[FinishScan, Done] {
      case (FinishScan(timestamp), ctx, state) =>
        ctx.thenPersist(
          ScanFinished(timestamp)
        ) {
          _ => ctx.reply(Done)
        }
    }.onCommand[ScanFailure, Done] {
    case (ScanFailure(timestamp, msg), ctx, state) =>
      ctx.thenPersist(
        ScanFailed(timestamp, msg)
      ) {
        _ => ctx.reply(Done)
      }
    }.onEvent {
      case (ScanStarted(timestamp), state) => state.start(timestamp)
      case (SearchUpdated(site, page, links), state) => state.updateSearch(site, page, links)
      case (ScanFinished(timestamp), state) => state
      case (ScanFailed(timestamp, msg), state) => state
    }.orElse(getScan)
  }

  private val getScan = Actions().onReadOnlyCommand[GetScan.type, Scan] { case (GetScan, ctx, state) => ctx.reply(state) }

}

/**
  * State
  * @param startedAt
  * @param searches
  */
case class Scan(startedAt: Option[Instant], searches: Seq[IxquickSearch]) {
  //@todo check if already started
  def start(timestamp: Instant): Scan = copy(startedAt = Some(timestamp), searches = Seq())

  def updateSearch(site: String, page: Int, links: Seq[String]): Scan = {
    val idx = searches.indexWhere(_.site == site)
    //if we found the search we update it
    if(idx != -1)
      copy(searches = searches.updated(idx, searches(idx).copy(last_scanned_page = page, links = searches(idx).links ++ links)))
    // else we create a new search entry
    else
      copy(searches = searches :+ IxquickSearch(site = site, last_scanned_page = page, links = links))
  }
}

object Scan {
  implicit val format: Format[Scan] = Json.format
}

case class IxquickSearch(site: String, last_scanned_page: Int, links: Seq[String])
object IxquickSearch {
  implicit val format: Format[IxquickSearch] = Json.format
}

/**
  * Commands
  */
sealed trait IxquickScannerCommand

case class StartScan(timestamp: Instant) extends IxquickScannerCommand with ReplyType[Done]

object StartScan {
  implicit val format: Format[StartScan] = Json.format[StartScan]
}

case class FinishScan(time: Instant) extends IxquickScannerCommand with ReplyType[Done]

object FinishScan {
  implicit val format: Format[FinishScan] = Json.format
}

case class ScanFailure(times: Instant, errorMsg: String) extends IxquickScannerCommand with ReplyType[Done]

object ScanFailure {
  implicit val format: Format[ScanFailure] = Json.format
}

case class UpdateSearch(site: String, page: Int, links: Seq[String]) extends IxquickScannerCommand with ReplyType[Done]
object UpdateSearch {
  implicit val format: Format[UpdateSearch] = Json.format
}


/**
  * Events
  */
object IxquickScannerEvent {
  val Tag = AggregateEventTag[IxquickScannerEvent]
}
sealed trait IxquickScannerEvent extends AggregateEvent[IxquickScannerEvent] {
  override def aggregateTag: AggregateEventTag[IxquickScannerEvent] = IxquickScannerEvent.Tag
}

case class ScanStarted(timestamp: Instant) extends IxquickScannerEvent

object ScanStarted {
  implicit val format: Format[ScanStarted] = Json.format
}

case class ScanFinished(timestamp: Instant) extends IxquickScannerEvent

object ScanFinished {
  implicit val format: Format[ScanFinished] = Json.format
}

case class ScanFailed(timestamp: Instant, errorMsg: String) extends IxquickScannerEvent
object ScanFailed {
  implicit val format: Format[ScanFailed] = Json.format
}

case class SearchUpdated(site: String, page: Int, links: Seq[String]) extends IxquickScannerEvent
object SearchUpdated {
  implicit val format: Format[SearchUpdated] = Json.format
}

case object GetScan extends IxquickScannerCommand with ReplyType[Scan] {
  implicit val format: Format[GetScan.type] = singletonFormat(GetScan)
}

object IxquickScanSerializerRegistry extends JsonSerializerRegistry {
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[Scan],
    JsonSerializer[IxquickSearch],
    JsonSerializer[StartScan],
    JsonSerializer[ScanStarted],
    JsonSerializer[ScanFinished],
    JsonSerializer[FinishScan],
    JsonSerializer[SearchUpdated],
    JsonSerializer[SearchUpdated],
    JsonSerializer[ScanFailed],
    JsonSerializer[ScanFailure],
    JsonSerializer[GetScan.type]
  )
}