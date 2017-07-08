package de.beuth.profile.scanner.impl

import java.time.Instant

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.ReplyType
import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}
import play.api.libs.json._

import scala.collection.immutable.Seq

/**
  * Created by David on 27.06.17.
  */
class ProfileScannerEntity extends PersistentEntity {
  override type Command = ProfileScannerCommand
  override type Event = ProfileScannerEvent
  override type State = Profiles

  override def initialState: Profiles = Profiles(Instant.now(), Seq[Profile]())

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

case class Profiles(timestamp: Instant, profiles: Seq[Profile])
object Profiles {
  implicit val format: Format[Profiles] = Json.format
}

case class Profile(firstname: String,
                   lastname: String,
                   updatedAt: Instant,
                   link: ProfileLink,
                  // extra: Map[String, Option[String]],
                   skills: Seq[String],
                   exp: Seq[JobExperience]
                  )
object Profile {
  implicit val format: Format[Profile] = Json.format
//  implicit val extraWrites: Writes[Map[String, Option[String]]] = Writes {Json.toJson(_)}
//  implicit val extraReads: Reads[Map[String, Option[String]]] = {
//    (__).read[Map[String, Option[String]]]
// }
}

case class JobExperience(title: String, company: Option[String], from: Option[String], to: Option[String], description: Option[String], isCurrent: Option[Boolean])
object JobExperience {
  implicit val format: Format[JobExperience] = Json.format
}

sealed trait ProfileLink{
  def url: String
  def name: String
}

object ProfileLink {
  implicit val reads: Reads[ProfileLink] = {
    (__ \ "name").read[String].flatMap {
      case "linkedin" => implicitly[Reads[LinkedinProfileLink]].map(identity)
      case "xing" => implicitly[Reads[XingProfileLink]].map(identity)
      case other => (Reads(_ => JsError(s"Unkown ProfileLink name $other")))
    }
  }

  implicit val writes: Writes[ProfileLink] = Writes { profileLink =>
    profileLink match {
      case m: LinkedinProfileLink => Json.toJson(m)(LinkedinProfileLink.format)
      case m: XingProfileLink => Json.toJson(m)(XingProfileLink.format)
    }
  }
}

case class LinkedinProfileLink(url: String) extends ProfileLink { val name = "linkedin" }
object LinkedinProfileLink {
  implicit val format: Format[LinkedinProfileLink] = Json.format
}
case class XingProfileLink(url: String) extends ProfileLink { val name = "xing" }
object XingProfileLink {
  implicit val format: Format[XingProfileLink] = Json.format
}


trait ProfileScannerCommand

//@todo may rename to something like AddProfile ProfileUpdate etc...
case class ScanProfile(timestamp: Instant, profile: Profile) extends ProfileScannerCommand with ReplyType[Done]
object ScanProfile {
  implicit val format: Format[ScanProfile] = Json.format
}

case class ProfileScanFailure(url: String, timestamp: Instant, errorMsg: String)  extends ProfileScannerCommand with ReplyType[Done]
object ProfileScanFailure {
  implicit val format: Format[ProfileScanFailure] = Json.format
}

case class StartScan(timestamp: Instant, urls: Seq[String]) extends ProfileScannerCommand with ReplyType[Done]
object StartScan {
  implicit val format: Format[StartScan] = Json.format
}


case class FinishScan(timestamp: Instant, urls: Seq[String]) extends ProfileScannerCommand with ReplyType[Done]
object FinishScan {
  implicit val format: Format[FinishScan] = Json.format
}

trait ProfileScannerEvent

case class ProfileScanned(timestamp: Instant, profile: Profile) extends ProfileScannerEvent
object ProfileScanned {
  implicit val format: Format[ProfileScanned] = Json.format
}

case class ProfileScanFailed(url: String, timestamp: Instant, errorMsg: String) extends ProfileScannerEvent
object ProfileScanFailed {
  implicit val format: Format[ProfileScanFailed] = Json.format
}

case class ScanStarted(timestamp: Instant, urls: Seq[String]) extends ProfileScannerEvent
object ScanStarted {
  implicit val format: Format[ScanStarted] = Json.format
}

case class ScanFinished(timestamp: Instant, urls: Seq[String]) extends ProfileScannerEvent
object ScanFinished {
  implicit val format: Format[ScanFinished] = Json.format
}

object ProfileScanSerializerRegistry extends JsonSerializerRegistry {
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[Profiles],
    JsonSerializer[Profile],
    JsonSerializer[LinkedinProfileLink],
    JsonSerializer[XingProfileLink],
    JsonSerializer[JobExperience],
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