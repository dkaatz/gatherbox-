package de.beuth.censys.scanner.impl

import java.time.Instant

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventTag, PersistentEntity}
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.ReplyType
import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}
import de.beuth.censys.api.{CensysIpv4Result, CensysIpv4SearchResult}
import de.beuth.utils.JsonFormats.singletonFormat
import play.api.libs.json.{Format, Json}

import scala.collection.immutable.Seq

/**
  * Created by David on 08.06.17.
  */
class CensysScannerEntity extends PersistentEntity {
  override type Command = CensysScannerCommand
  override type Event = CensysScannerEvent
  override type State = Scan

  override def initialState: Scan = Scan(None, Seq[CensysIpv4Result](), false)

  override def behavior: Behavior = {
    case scan => Actions().onCommand[StartScan, Done] {
      case (StartScan(timestamp), ctx, state) if isRunning(state) =>
        ctx.invalidCommand(s"Scan for $entityId already running...")
        ctx.done
      case (StartScan(timestamp), ctx, state) =>
        ctx.thenPersist(
          ScanStarted(timestamp)
        ) {
          _ => ctx.reply(Done)
        }
    }.onCommand[UpdateScan, Done] {
      case (UpdateScan(ipv4), ctx, state) =>
        ctx.thenPersist(
          ScanUpdated(ipv4)
        ) {
          _ => ctx.reply(Done)
        }
    }.onCommand[FinishScan, Done] {
      case (FinishScan(timestamp), ctx, state)  if isRunning(state) =>
        ctx.thenPersist(
          ScanFinished(timestamp)
        ) {
          _ => ctx.reply(Done)
        }
      case (FinishScan(timestamp), ctx, state) =>
        ctx.invalidCommand(s"Scan did not started yet or already finished...")
        ctx.done

    }.onEvent {
      case (ScanStarted(timestamp), state) => state.start(timestamp)
      case (ScanUpdated(ipv4), state) => state.update(ipv4)
      case (ScanFinished(timestamp), state) => state.finish
    }.orElse(getScan)
  }

  private def isRunning(state: Scan): Boolean = !state.finished && state.startedAt.isDefined

  private val getScan = Actions().onReadOnlyCommand[GetScan.type, Scan] {  case (GetScan, ctx, state) => ctx.reply(state) }


}

case class Scan(startedAt: Option[Instant], ipv4: Seq[CensysIpv4Result], finished: Boolean) {
  def start(timestamp: Instant): Scan = copy(startedAt=Some(timestamp), ipv4 = Seq[CensysIpv4Result](), finished = false)
  def update(ipv4: Seq[CensysIpv4Result]): Scan = copy(ipv4 = this.ipv4 ++ ipv4)
  def finish = copy(finished = true)
}

object Scan {
  implicit val format: Format[Scan] = Json.format
}

sealed trait CensysScannerCommand

case class StartScan(timestamp: Instant) extends CensysScannerCommand with ReplyType[Done]
object StartScan {
  implicit val format: Format[StartScan] = Json.format[StartScan]
}

case class FinishScan(time: Instant) extends CensysScannerCommand with ReplyType[Done]
object FinishScan {
  implicit val format: Format[FinishScan] = Json.format
}

case class UpdateScan(ipv4: Seq[CensysIpv4Result]) extends CensysScannerCommand with ReplyType[Done]
object UpdateScan {
  implicit val format: Format[UpdateScan] = Json.format
}

case object GetScan extends CensysScannerCommand with ReplyType[Scan] {
  implicit val format: Format[GetScan.type] = singletonFormat(GetScan)
}


sealed trait CensysScannerEvent extends AggregateEvent[CensysScannerEvent] {
  override def aggregateTag: AggregateEventTag[CensysScannerEvent] = CensysScannerEvent.Tag
}

object CensysScannerEvent {
  val Tag = AggregateEventTag[CensysScannerEvent]
}
sealed trait CensysScannerStatusEvent extends CensysScannerEvent  {}

sealed trait CensysScannerUpdateEvent extends CensysScannerEvent {}


case class ScanStarted(timestamp: Instant) extends CensysScannerStatusEvent
object ScanStarted { implicit val format: Format[ScanStarted] = Json.format }

case class ScanFinished(timestamp: Instant) extends CensysScannerStatusEvent
object ScanFinished { implicit val format: Format[ScanFinished] = Json.format }

case class ScanUpdated(ipv4: Seq[CensysIpv4Result]) extends CensysScannerUpdateEvent
object ScanUpdated { implicit val format: Format[ScanUpdated] = Json.format }



object ScanSerializerRegistry extends JsonSerializerRegistry {
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[Scan],
    JsonSerializer[StartScan],
    JsonSerializer[ScanStarted],
    JsonSerializer[ScanFinished],
    JsonSerializer[FinishScan],
    JsonSerializer[UpdateScan],
    JsonSerializer[ScanUpdated],
    JsonSerializer[GetScan.type]
  )
}