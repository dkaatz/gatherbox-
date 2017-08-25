package de.beuth.scanner.commons

import java.time.Instant

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventTag, PersistentEntity}
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.ReplyType
import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}
import play.api.libs.json.{Format, Json}

trait ScannerEntity extends PersistentEntity {

  override type Event = ScannerEvent
  override type Command = ScannerCommand
  override type State = ScannerState

  def scanStatusBehavior: Actions =  Actions().onCommand[StartScan, Done] {
    case (StartScan(timestamp), ctx, state: ScannerState) if state.startedAt.isDefined && !state.finished =>
      ctx.invalidCommand(s"Scan for $entityId already running.")
      ctx.done

    case (StartScan(timestamp), ctx, state) =>
      ctx.thenPersist(
        ScanStarted(timestamp)
      ) {
        _ => ctx.reply(Done)
      }

  }.onCommand[FinishScan, Done] {
    case (FinishScan(timestamp), ctx, state: ScannerState) if state.startedAt.isDefined && !state.finished =>
      ctx.thenPersist(
        ScanFinished(timestamp)
      ) {
        _ => ctx.reply(Done)
      }

    case (FinishScan(timestamp), ctx, state) =>
      ctx.invalidCommand(s"Scan for $entityId still running or did not started yet")
      ctx.done


  }.onCommand[ScanFailure, Done] {
    case (ScanFailure(timestamp, msg), ctx, state) =>
      ctx.thenPersist(
        ScanFailed(timestamp, msg)
      ) {
        _ => ctx.reply(Done)
      }
  }.onEvent {
    case (ScanStarted(timestamp), state: ScannerState) => state.start(timestamp)
    case (ScanFinished(timestamp), state: ScannerState) => state.finish
    case (ScanFailed(timestamp, errorMsg), state: ScannerState) => state.finish
  }
}

trait ScannerState {
  val startedAt: Option[Instant]
  val finished: Boolean

  def start(timestamp: Instant): ScannerState
  def finish(): ScannerState
}

trait ScannerCommand {}


case class StartScan(timestamp: Instant) extends ScannerCommand with ReplyType[Done]

object StartScan {
  implicit val format: Format[StartScan] = Json.format[StartScan]
}

case class FinishScan(time: Instant) extends ScannerCommand with ReplyType[Done]

object FinishScan {
  implicit val format: Format[FinishScan] = Json.format
}

case class ScanFailure(times: Instant, errorMsg: String) extends ScannerCommand with ReplyType[Done]

object ScanFailure {
  implicit val format: Format[ScanFailure] = Json.format
}

object ScannerEvent {
  val Tag = AggregateEventTag[ScannerEvent]
}

sealed trait ScannerEvent extends AggregateEvent[ScannerEvent] {
  override def aggregateTag: AggregateEventTag[ScannerEvent] = ScannerEvent.Tag
}

trait ScannerStatusEvent extends ScannerEvent {}
trait ScannerUpdateEvent extends ScannerEvent {}

case class ScanStarted(timestamp: Instant) extends ScannerStatusEvent

object ScanStarted {
  implicit val format: Format[ScanStarted] = Json.format
}

case class ScanFinished(timestamp: Instant) extends ScannerStatusEvent

object ScanFinished {
  implicit val format: Format[ScanFinished] = Json.format
}

case class ScanFailed(timestamp: Instant, errorMsg: String) extends ScannerStatusEvent
object ScanFailed {
  implicit val format: Format[ScanFailed] = Json.format
}

object ScannerSerialzierRegistry extends JsonSerializerRegistry {
  override def serializers: scala.collection.immutable.Seq[JsonSerializer[_]] = scala.collection.immutable.Seq(
    JsonSerializer[StartScan],
    JsonSerializer[ScanStarted],
    JsonSerializer[ScanFinished],
    JsonSerializer[FinishScan],
    JsonSerializer[ScanFailed],
    JsonSerializer[ScanFailure]
  )
}