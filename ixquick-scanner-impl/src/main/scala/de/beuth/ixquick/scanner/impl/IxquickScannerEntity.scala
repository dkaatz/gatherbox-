package de.beuth.ixquick.scanner.impl

import java.time.Instant

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventTag, PersistentEntity}
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.ReplyType
import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}
import de.beuth.scan.api.ScannerStatus
import de.beuth.utils.JsonFormats.singletonFormat
import play.api.libs.json.{Format, Json}
import de.beuth.scanner.commons._

import scala.collection.immutable.Seq

/**
  * Created by David on 08.06.17.
  */
class IxquickScannerEntity extends ScannerEntity {
  override def initialState: Scan = Scan(None, Seq(), false)

  override def behavior: Behavior = {
    scanStatusBehavior.orElse(
    Actions().onCommand[UpdateSearch, Done] {
      case (UpdateSearch(site, page, links), ctx, state) =>
        ctx.thenPersist(
          SearchUpdated(site, page, links)
        ) {
          _ => ctx.reply(Done)
        }
    }.onEvent {
      case (SearchUpdated(site, page, links), state: Scan) => state.updateSearch(site, page, links)
    }).orElse(getScan)
  }

  private val getScan = Actions().onReadOnlyCommand[GetScan.type, Scan] { case (GetScan, ctx, state: Scan) => ctx.reply(state) }
}

/**
  * State
  * @param startedAt
  * @param searches
  */
case class Scan(startedAt: Option[Instant], searches: Seq[IxquickSearch], finished: Boolean) extends ScannerState {
  def start(timestamp: Instant): Scan = copy(startedAt = Some(timestamp), searches = Seq(), finished = false)

  def updateSearch(site: String, page: Int, links: Seq[String]): Scan = {
    val idx = searches.indexWhere(_.site == site)
    //if we found the search we update it
    if(idx != -1)
      copy(searches = searches.updated(idx, searches(idx).copy(last_scanned_page = page, links = searches(idx).links ++ links)))
    // else we create a new search entry
    else
      copy(searches = searches :+ IxquickSearch(site = site, last_scanned_page = page, links = links))
  }

  def finish = copy(finished = true)
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
sealed trait IxquickScannerCommand extends ScannerCommand

case class UpdateSearch(site: String, page: Int, links: Seq[String]) extends IxquickScannerCommand with ReplyType[Done]
object UpdateSearch {
  implicit val format: Format[UpdateSearch] = Json.format
}

case object GetScan extends IxquickScannerCommand with ReplyType[Scan] {
  implicit val format: Format[GetScan.type] = singletonFormat(GetScan)
}

case class SearchUpdated(site: String, page: Int, links: Seq[String]) extends ScannerUpdateEvent
object SearchUpdated {
  implicit val format: Format[SearchUpdated] = Json.format
}

object IxquickScanSerializerRegistry extends JsonSerializerRegistry {
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[Scan],
    JsonSerializer[IxquickSearch],
    JsonSerializer[UpdateSearch],
    JsonSerializer[SearchUpdated],
    JsonSerializer[GetScan.type]
  ) ++ ScannerSerialzierRegistry.serializers
}