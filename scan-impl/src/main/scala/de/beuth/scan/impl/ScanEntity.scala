package de.beuth.scan.impl

import java.time.LocalDateTime

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.ReplyType
import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}
import de.beuth.scan.api.ScanResult
import de.beuth.utils.JsonFormats._
import play.api.libs.json.{Format, Json}

import scala.collection.immutable.Seq

/**
  * Created by David on 06.06.17.
  */
class ScanEntity extends PersistentEntity {
  override type Command = ScanCommand
  override type Event = ScanEvent
  override type State = Option[Scan]

  override def initialState: Option[Scan] = None

  override def behavior: Behavior = {
    case Some(scan) => Actions().onCommand[FinishScan, Scan] {
        case (FinishScan(result), ctx: CommandContext[Scan], state) =>
          ctx.thenPersist(
            ScanFinished(LocalDateTime.now().toString, result)
          ) {
            res => ctx.reply(Scan(res.timestamp, res.result))
          }
      }.onCommand[StartScan, String] {
        case (StartScan(timestamp),  ctx: CommandContext[String], state) =>
          ctx.thenPersist(
            ScanStarted(LocalDateTime.now().toString)
          ) {
            _ => ctx.reply(LocalDateTime.now().toString)
          }
      }.onReadOnlyCommand[GetScan.type, Option[Scan]] {
        case (GetScan,  ctx: CommandContext[Option[Scan]], state: Option[Scan]) => ctx.reply(state)
      } onEvent {
        case (ScanStarted(timestamp), state) =>  Some(Scan(LocalDateTime.now().toString, ScanResult(LocalDateTime.now().toString, None)))
        case (ScanFinished(timestamp, result), state) => Some(Scan(LocalDateTime.now().toString, result))
      }
    case None =>
      Actions().onCommand[StartScan, String] {
        case (StartScan(timestamp), ctx: CommandContext[String], state) =>
          ctx.thenPersist(
            ScanStarted(LocalDateTime.now().toString)
          ) {
            _ => ctx.reply(LocalDateTime.now().toString)
          }
      }.onReadOnlyCommand[GetScan.type, Option[Scan]] {
        case (GetScan,  ctx: CommandContext[Option[Scan]], state: Option[Scan]) => ctx.reply(state)
      }.onEvent {
        case (ScanStarted(timestamp), state) =>  Some(Scan(LocalDateTime.now().toString, ScanResult(LocalDateTime.now().toString, None)))

      }
  }
}

/**
  * The current state held by the persistent entity.
  */
case class Scan(timestamp: String, result: ScanResult)

object Scan {
  implicit val format: Format[Scan] = Json.format
}

sealed trait ScanEvent


case class ScanStarted(timestamp: String) extends ScanEvent
object ScanStarted {
  implicit val format: Format[ScanStarted] = Json.format
}


case class ScanFinished(timestamp: String, result: ScanResult) extends ScanEvent
object ScanFinished {
  implicit val format: Format[ScanFinished] = Json.format
}

sealed trait ScanCommand

case class StartScan(timestamp: String) extends ScanCommand with ReplyType[String]

object StartScan {
  implicit val format: Format[StartScan] = Json.format[StartScan]
}

case class FinishScan(result: ScanResult) extends ScanCommand with ReplyType[Scan]
object FinishScan {
  implicit val format: Format[FinishScan] = Json.format
}

case object GetScan extends ScanCommand with ReplyType[Option[Scan]] {
  implicit val format: Format[GetScan.type] = singletonFormat(GetScan)
}

object ScanSerializerRegistry extends JsonSerializerRegistry {
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[Scan],
    JsonSerializer[StartScan],
    JsonSerializer[ScanStarted],
    JsonSerializer[GetScan.type],
    JsonSerializer[ScanFinished],
    JsonSerializer[FinishScan]
  )
}