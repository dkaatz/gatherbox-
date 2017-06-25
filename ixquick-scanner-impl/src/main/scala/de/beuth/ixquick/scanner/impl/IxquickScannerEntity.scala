package de.beuth.ixquick.scanner.impl

import java.time.Instant

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity
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

  override def initialState: Scan = Scan(None, Profiles())

  override def behavior: Behavior = {
    case scan => Actions().onCommand[StartScan, Done] {
      case (StartScan(timestmap), ctx, state) =>
        ctx.thenPersist(
          ScanStarted(timestmap)
        ) {
          _ => ctx.reply(Done)
        }
    }.onCommand[UpdateLinkedIn, Done] {
      case (UpdateLinkedIn(profiles), ctx, state) =>
        ctx.thenPersist(
          LinkedinUpdated(profiles)
        ) {
          _ => ctx.reply(Done)
        }
    }.onCommand[UpdateXing, Done] {
      case (UpdateXing(profiles), ctx, state) =>
        ctx.thenPersist(
          XingUpdated(profiles)
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
    }.onEvent {
      case (ScanStarted(timestamp), state) => state.start(timestamp)
      case (LinkedinUpdated(profiles), state) => state.updateLinkedinProfiles(profiles)
      case (XingUpdated(profiles), state) => state.updateXingProfiles(profiles)
      case (ScanFinished(timestamp), state) => state
    }.orElse(getScan)
  }

  private val getScan = Actions().onReadOnlyCommand[GetScan.type, Scan] {  case (GetScan, ctx, state) => ctx.reply(state) }

}

case class Scan(startedAt: Option[Instant], profiles: Profiles) {
  def start(timestamp: Instant): Scan = copy(startedAt=Some(timestamp), profiles = Profiles())
  def updateLinkedinProfiles(profileLinks: Seq[String]): Scan = copy(profiles = this.profiles.updateLinkedin(profileLinks))
  def updateXingProfiles(profileLinks: Seq[String]): Scan = copy(profiles = this.profiles.updateXing(profileLinks))
}
object Scan {
  implicit val format: Format[Scan] = Json.format
}


case class Profiles(linkedin: Seq[String] = Seq[String](), xing: Seq[String] = Seq[String]()) {
  def updateLinkedin(profiles: Seq[String]) = copy(linkedin = (linkedin ++ profiles).distinct)
  def updateXing(profiles: Seq[String]) = copy(xing = (xing ++ profiles).distinct)
}

object Profiles {
  implicit val format: Format[Profiles] = Json.format
}

sealed trait IxquickScannerCommand

case class StartScan(timestamp: Instant) extends IxquickScannerCommand with ReplyType[Done]
object StartScan {
  implicit val format: Format[StartScan] = Json.format[StartScan]
}

case class FinishScan(time: Instant) extends IxquickScannerCommand with ReplyType[Done]
object FinishScan {
  implicit val format: Format[FinishScan] = Json.format
}

case class UpdateLinkedIn(profiles: Seq[String]) extends IxquickScannerCommand with ReplyType[Done]
object UpdateLinkedIn {
  implicit val format: Format[UpdateLinkedIn] = Json.format
}

case class UpdateXing(profiles: Seq[String]) extends IxquickScannerCommand with ReplyType[Done]
object UpdateXing {
  implicit val format: Format[UpdateXing] = Json.format
}

sealed trait IxquickScannerEvent

case class ScanStarted(timestamp: Instant) extends IxquickScannerEvent
object ScanStarted { implicit val format: Format[ScanStarted] = Json.format }

case class ScanFinished(timestamp: Instant) extends IxquickScannerEvent
object ScanFinished { implicit val format: Format[ScanFinished] = Json.format }

case class LinkedinUpdated(profiles: Seq[String]) extends IxquickScannerEvent
object LinkedinUpdated { implicit val format: Format[LinkedinUpdated] = Json.format }

case class XingUpdated(profiles: Seq[String]) extends IxquickScannerEvent
object XingUpdated { implicit val format: Format[XingUpdated] = Json.format }



case object GetScan extends IxquickScannerCommand with ReplyType[Scan] {
  implicit val format: Format[GetScan.type] = singletonFormat(GetScan)
}

object IxquickScanSerializerRegistry extends JsonSerializerRegistry {
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[Scan],
    JsonSerializer[Profiles],
    JsonSerializer[StartScan],
    JsonSerializer[ScanStarted],
    JsonSerializer[ScanFinished],
    JsonSerializer[FinishScan],
    JsonSerializer[UpdateXing],
    JsonSerializer[XingUpdated],
    JsonSerializer[UpdateLinkedIn],
    JsonSerializer[LinkedinUpdated],
    JsonSerializer[GetScan.type]
  )
}