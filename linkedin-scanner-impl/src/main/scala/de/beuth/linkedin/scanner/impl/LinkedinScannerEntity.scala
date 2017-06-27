package de.beuth.linkedin.scanner.impl

import java.time.Instant

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.ReplyType
import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}
import play.api.libs.json.{Format, Json}

/**
  * Created by David on 27.06.17.
  */
class LinkedinScannerEntity extends PersistentEntity {
  override type Command = LinkedinScannerCommand
  override type Event = LinkedinScannerEvent
  override type State = LinkedinProfiles

  override def initialState: LinkedinProfiles = LinkedinProfiles(Instant.now(), Seq[LinkedinProfile]())

  override def behavior: Behavior = {
    case scan => Actions().onCommand[StartScan, Done] {
      case (StartScan(timestamp, urls), ctx, state) =>
        ctx.thenPersist(
          ScanStarted(timestamp, urls)
        ) {
          _ => ctx.reply(Done)
        }
    }.onCommand[FinishScan, Done] {
      case (FinishScan(timestamp, urls), ctx, state) =>
        ctx.thenPersist(
          ScanFinished(timestamp, urls)
        ) {
          _ => ctx.reply(Done)
        }
    }.onCommand[ScanProfile, Done] {
      case (ScanProfile(timestamp, profile), ctx, state) =>
        ctx.thenPersist(
          ProfileScanned(timestamp, profile)
        ) {
          _ => ctx.reply(Done)
        }
    }.onCommand[ProfileScanFailure, Done] {
      case (ProfileScanFailure(url, timestamp, msg), ctx, state) =>
        ctx.thenPersist(
          ProfileScanFailed(url, timestamp, msg)
        ) {
          _ => ctx.reply(Done)
        }
    }.onEvent{
      case (ScanStarted(timestamp, urls), state) => state
      case (ScanFinished(timestamp, urls), state) => state
      case (ProfileScanned(timestamp, profile), state) => state
      case (ProfileScanFailed(url, timestamp, msg), state) => state
    }
  }
}

case class LinkedinProfiles(timestamp: Instant, profiles: Seq[LinkedinProfile])
object LinkedinProfiles {
  implicit val format: Format[LinkedinProfiles] = Json.format
}


case class LinkedinProfile(url: String, timestamp: Instant, raw: String, data: Map[String, String])
object LinkedinProfile {
  implicit val format: Format[LinkedinProfile] = Json.format
}


trait LinkedinScannerCommand

//@todo may rename to something like AddProfile ProfileUpdate etc...
case class ScanProfile(timestamp: Instant, profile: LinkedinProfile) extends LinkedinScannerCommand with ReplyType[Done]
object ScanProfile {
  implicit val format: Format[ScanProfile] = Json.format
}

case class ProfileScanFailure(url: String, timestamp: Instant, errorMsg: String)  extends LinkedinScannerCommand with ReplyType[Done]
object ProfileScanFailure {
  implicit val format: Format[ProfileScanFailure] = Json.format
}

case class StartScan(timestamp: Instant, urls: Seq[String]) extends LinkedinScannerCommand with ReplyType[Done]
object StartScan {
  implicit val format: Format[FinishScan] = Json.format
}


case class FinishScan(timestamp: Instant, urls: Seq[String]) extends LinkedinScannerCommand with ReplyType[Done]
object FinishScan {
  implicit val format: Format[FinishScan] = Json.format
}

trait LinkedinScannerEvent

case class ProfileScanned(timestamp: Instant, profile: LinkedinProfile) extends LinkedinScannerEvent
object ProfileScanned {
  implicit val format: Format[ProfileScanned] = Json.format
}

case class ProfileScanFailed(url: String, timestamp: Instant, errorMsg: String) extends LinkedinScannerEvent
object ProfileScanFailed {
  implicit val format: Format[ProfileScanFailed] = Json.format
}

case class ScanStarted(timestamp: Instant, urls: Seq[String]) extends LinkedinScannerEvent
object ScanStarted {
  implicit val format: Format[ScanStarted] = Json.format
}

case class ScanFinished(timestamp: Instant, urls: Seq[String]) extends LinkedinScannerEvent
object ScanFinished {
  implicit val format: Format[ScanFinished] = Json.format
}

object IxquickScanSerializerRegistry extends JsonSerializerRegistry {
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[LinkedinProfiles],
    JsonSerializer[LinkedinProfile],
    JsonSerializer[StartScan],
    JsonSerializer[ScanStarted],
    JsonSerializer[ScanFinished],
    JsonSerializer[FinishScan],
    JsonSerializer[ScanProfile],
    JsonSerializer[ProfileScanned],
    JsonSerializer[ProfileScanFailed],
    JsonSerializer[ProfileScanFailure]
  )
}