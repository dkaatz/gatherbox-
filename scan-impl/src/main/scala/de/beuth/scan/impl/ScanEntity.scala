package de.beuth.scan.impl

import java.time.{Instant, LocalDateTime}

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventTag, PersistentEntity}
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.ReplyType
import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}
import de.beuth.utils.JsonFormats._
import play.api.libs.json.{Format, Json}

import scala.collection.immutable.Seq

/**
  * Created by David on 06.06.17.
  */
class ScanEntity extends PersistentEntity {
  override type Command = ScanCommand
  override type Event = ScanEvent
  override type State = Scan

  override def initialState: Scan = Scan(None, Seq[Scanner]())

  override def behavior: Behavior = {
    case scan => updateScan
  }

  private val getScan = Actions().onReadOnlyCommand[GetScan.type, Scan] {  case (GetScan, ctx, state) => ctx.reply(state) }

  private val updateScan = Actions().onCommand[StartScan, Scan] {
    case (StartScan(startedAt), ctx, state) =>
      ctx.thenPersist(
        ScanStarted(startedAt)
      ) {
        _ => ctx.reply(state.start(startedAt))
      }
    }.onCommand[RegisterScanner, Scan] {
      case (RegisterScanner(timestamp, name), ctx, state) =>
        ctx.thenPersist(
          ScannerRegistered(timestamp, name)
        ) {
          _ => ctx.reply(state.registerScanner(name, timestamp))
        }
    }.onCommand[UnregisterScanner, Scan] {
      case (UnregisterScanner(name), ctx, state) =>
        ctx.thenPersist(
          ScannerUnregistered(name)
        ) {
          _ => ctx.reply(state.unregisterScanner(name))
        }
    }.onCommand[UpdateScanner, Scan] {
    case (UpdateScanner(name, status), ctx, state) =>
      ctx.thenPersist(
        ScannerUpdated(name, status)
      ) {
        _ => ctx.reply(state.updateScannerStatus(name, status))
      }
    }.onCommand[FailedScan, Done] {
    case (FailedScan(timestamp, errorMsg), ctx, state) =>
      ctx.thenPersist(
        ScanFailed(timestamp, errorMsg)
      ) {
        _ => Done
      }
    }
    .onEvent {
      case (ScanStarted(timestamp), scan) => scan.start(timestamp)
      case (ScannerRegistered(timestamp, name), scan) => scan.registerScanner(name, timestamp)
      case (ScannerUnregistered(name), scan) => scan.unregisterScanner(name)
      case (ScannerUpdated(name, status), scan) => scan.updateScannerStatus(name, status)
      case (ScanFailed(timestamp, errorMsg), scan) => scan
    }.orElse(getScan)
}

/**
  * The current state held by the persistent entity.
  */
case class Scan(startedAt: Option[Instant], scanner: Seq[Scanner]) {

  def start(timestamp: Instant): Scan = {
    copy(
      startedAt = Some(timestamp),
      scanner = scanner.map {
        s => if(s.status == ScannerStatus.Scanned) s.copy(status = ScannerStatus.ScanPending) else s
      }
    )
  }

  def isScanFinished(): Boolean = scanner.find(s => s.status != ScannerStatus.Scanned || (s.status != ScannerStatus.Unscanned && s.registeredAt.isAfter(startedAt.getOrElse(Instant.now())))).isEmpty

  def registerScanner(name: String, timestamp: Instant): Scan = {
    val newScanner: Scanner = Scanner(name: String, registeredAt = timestamp, status = ScannerStatus.Unscanned)

    if(scanner.par.find(_.name == name).isEmpty) {
      copy(scanner = scanner :+ newScanner)
    } else {
      copy(scanner = scanner.map(s => if(s.name == name) newScanner else s))
    }


  }

  def unregisterScanner(name: String): Scan = {
    if(scanner.find(_.name == name).isEmpty)
      throw new ScannerNotRegisteredException()
    copy(
      scanner = scanner.filter(_.name != name)
    )
  }

  def updateScannerStatus(name: String, status: ScannerStatus.Status): Scan = {
    if(scanner.find(_.name == name).isEmpty)
      throw new ScannerNotRegisteredException()
    copy(
      scanner = scanner.map(s => if(s.name == name) s.copy(status = status) else s)
    )
  }
}

object Scan {
  implicit val format: Format[Scan] = Json.format
}

case class Scanner(name: String, registeredAt: Instant, status: ScannerStatus.Status)

object Scanner {
  implicit val format: Format[Scanner] = Json.format
}

object ScannerStatus extends Enumeration {
  val Unscanned, ScanPending, Scanning, Scanned = Value
  type Status = Value
  implicit val format: Format[Status] = enumFormat(ScannerStatus)
}

/**
  * Events
  */

object ScanEvent {
  val Tag = AggregateEventTag[ScanEvent]
}
sealed trait ScanEvent extends AggregateEvent[ScanEvent] {
  override def aggregateTag: AggregateEventTag[ScanEvent] = ScanEvent.Tag
}

case class ScanFailed(timestamp: Instant, errorMsg: String) extends ScanEvent
object ScanFailed {
  implicit val format: Format[ScanFailed] = Json.format
}

case class ScanStarted(timestamp: Instant) extends ScanEvent
object ScanStarted {
  implicit val format: Format[ScanStarted] = Json.format
}

case class ScanFinished(timestamp: Instant) extends ScanEvent
object ScanFinished {
  implicit val format: Format[ScanFinished] = Json.format
}

case class ScannerRegistered(timestamp: Instant, name: String) extends ScanEvent
object ScannerRegistered {
  implicit val format: Format[ScannerRegistered] = Json.format
}

case class ScannerUnregistered(name: String) extends ScanEvent
object ScannerUnregistered {
  implicit val format: Format[ScannerUnregistered] = Json.format
}

case class ScannerUpdated(name: String, status: ScannerStatus.Status) extends ScanEvent
object ScannerUpdated {
  implicit val format: Format[ScannerUpdated] = Json.format
}

/**
  * Commands
  */
sealed trait ScanCommand

case class FailedScan(timestamp: Instant, errorMsg: String) extends ScanCommand with ReplyType[Done]

object FailedScan {
  implicit val format: Format[FailedScan] = Json.format
}


case class StartScan(timestamp: Instant) extends ScanCommand with ReplyType[Scan]

object StartScan {
  implicit val format: Format[StartScan] = Json.format[StartScan]
}

case class FinishScan(time: Instant) extends ScanCommand with ReplyType[Scan]
object FinishScan {
  implicit val format: Format[FinishScan] = Json.format
}

case class RegisterScanner(timestamp: Instant, name: String) extends ScanCommand with ReplyType[Scan]
object RegisterScanner {
  implicit val format: Format[RegisterScanner] = Json.format
}


case class UnregisterScanner(name: String) extends ScanCommand with ReplyType[Scan]
object UnregisterScanner {
  implicit val format: Format[UnregisterScanner] = Json.format
}


case class UpdateScanner(name: String, status: ScannerStatus.Status) extends ScanCommand with ReplyType[Scan]
object UpdateScanner {
  implicit val format: Format[UpdateScanner] = Json.format
}


case object GetScan extends ScanCommand with ReplyType[Scan] {
  implicit val format: Format[GetScan.type] = singletonFormat(GetScan)
}

object ScanSerializerRegistry extends JsonSerializerRegistry {
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[Scan],
    JsonSerializer[StartScan],
    JsonSerializer[ScanStarted],
    JsonSerializer[GetScan.type],
    JsonSerializer[ScanFinished],
    JsonSerializer[FinishScan],
    JsonSerializer[ScannerRegistered],
    JsonSerializer[RegisterScanner],
    JsonSerializer[UnregisterScanner],
    JsonSerializer[ScannerUnregistered],
    JsonSerializer[UpdateScanner],
    JsonSerializer[ScannerUpdated]
  )
}

class ScannerAlreadyRegisteredException extends Exception
class ScannerNotRegisteredException extends Exception