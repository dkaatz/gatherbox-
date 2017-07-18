package de.beuth.profile.scanner.impl

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventTag, PersistentEntity}
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.ReplyType
import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}
import play.api.libs.json._
import de.beuth.utils.JsonFormats.singletonFormat

import scala.collection.immutable.Seq

/**
  * Perstence Entity for a profile scan indexed by keyword
  */
class ProfileScannerEntity extends PersistentEntity {
  override type Command = ProfileScannerCommand
  override type Event = ProfileScannerEvent
  override type State = Profiles

  /**
    * Emtpy list of Profiles as Initial State of the entity
    */
  override def initialState: Profiles = Profiles(Instant.now(), Seq())

  override def behavior: Behavior = {
    case scan => Actions()

      /**
        * On StartScan Command filter out URL's that should not get scanned due to they recently got scanned and return
        * the list of URL's that get actualy scanned
        */
      .onCommand[StartScan, Seq[String]] {
      case (StartScan(timestamp, urls), ctx, state) =>
        //getting all already present profiles that are not out dated
        val notOutDated = state.profiles.filter(profile => timestamp.minus(12, ChronoUnit.HOURS).isBefore(profile.updatedAt))
        //filter out all profile urls that do exist in the list of profiles that are not outdated yet
        val linksToScan = urls.filterNot(url => notOutDated.exists(profile => profile.link.url == url))
        ctx.thenPersist(
          ScanStarted(timestamp, linksToScan)
        ) {
          _ => ctx.reply(linksToScan)
        }

    }
    /**
      * On Finish scan persist the event and return done
       */
    .onCommand[FinishScan, Done] {
      case (FinishScan(timestamp, urls), ctx, state) =>
        ctx.thenPersist(
          ScanFinished(timestamp, urls)
        ) {
          _ => ctx.reply(Done)
        }
    }

    /**
      * On UpdateProfile persist the event and reply with done
      */
    .onCommand[UpdateProfile, Done] {
      case (UpdateProfile(timestamp, profile), ctx, state) =>
        ctx.thenPersist(
          ProfileUpdated(timestamp, profile)
        ) {
          _ => ctx.reply(Done)
        }
    /**
      * On Failure  persist the event and return done
      */
    }.onCommand[ProfileScanFailure, Done] {
      case (ProfileScanFailure(url, timestamp, msg), ctx, state) =>
        ctx.thenPersist(
          ProfileScanFailed(url, timestamp, msg)
        ) {
          _ => ctx.reply(Done)
        }
    }.onEvent{
      /**
        * Events that change the state
        */
      case (ScanStarted(timestamp, urls), state) => state.startScan(timestamp, urls)
      case (ProfileUpdated(timestamp, profile), state) => state.updateProfile(timestamp, profile)
    /**
      *  events that do not change the state but are persisted for communication integrity
      */
      case (ScanFinished(timestamp, urls), state) => state
      case (ProfileScanFailed(url, timestamp, msg), state) => state
    }.orElse(getProfiles)
  }

  /**
    *  Handler for Read-Only Command
    */
  private val getProfiles = Actions().onReadOnlyCommand[GetProfiles.type, Profiles] { case (GetProfiles, ctx, state) => ctx.reply(state) }
}

/**
  * Represents the state of an Entity for a specific keyword wich is the last updated time and the list of profiles where
  * a Profile does not have to contain the extracted data and may be only the link to the proile
  *
  * @param updatedAt describes when the profiles where updated last
  * @param profiles list of profiles (a profile may contains just the link)
  */
case class Profiles(updatedAt: Instant, profiles: Seq[Profile]) {

  /**
    * Initializes all not already initialized given Urls and adds them to the list of Profiles
    *
    * @param timestamp time when event occured
    * @param urls list of urls to initialize if not already present
    * @return updated entity
    */
  def startScan(timestamp: Instant, urls: Seq[String]): Profiles = {
    val newUrls = urls.filter(url => profiles.exists(profile => profile.link.url == url))
    copy(
      updatedAt = timestamp,
      // join the existing sequence of profiles with the new initiated profiles
      profiles = profiles ++ newUrls.map(url => Profile(
        firstname = None,
        lastname = None,
        skills = Seq(),
        exp = Seq(),
        updatedAt = timestamp,
        link = ProfileLink(
          url = url,
          provider = ProfileLink.deriveProvider(url)))))
  }

  /**
    * Overrides the given Profile if it is already initialized by any ScanStarted event
    *
    * @param timestamp time the event got sent
    * @param profile profile to update
    * @return the updated entity
    */
  def updateProfile(timestamp: Instant, profile: Profile): Profiles = {
    val pIdx = profiles.indexWhere(_.link.url == profile.link.url)

    if(pIdx == -1) throw ProfileScrapingException("Trying to update uninitialized Profile: " + Json.toJson(profile).toString())

    copy(
      updatedAt = timestamp,
      profiles = profiles.updated(pIdx, profile)
    )
  }


}

object Profiles {
  implicit val format: Format[Profiles] = Json.format
}

/**
  * Represents the extracted Profile Data
  *
  * @param firstname  givenname of the person
  * @param lastname surname of the person
  * @param updatedAt  date when the profile got updated by any scan event the last time
  * @param link link to the profile
  * @param skills list of skills extracted from the profile
  * @param exp list of jobs extracted from the profile
  */
case class Profile(firstname: Option[String],
                   lastname: Option[String],
                   updatedAt: Instant,
                   link: ProfileLink,
                   skills: Seq[String],
                   exp: Seq[JobExperience]
                  )
object Profile {
  implicit val format: Format[Profile] = Json.format
}

/**
  * Represents a Job Experience described in a profile
  *
  *
  * @param title  job title
  * @param company company of this expierience
  * @param from string representing the date when the user started to work at this job
  * @param to string representing the date when the user ended to work at this job
  * @param description describtion of the job
  * @param isCurrent describes if this is the current job
  */
case class JobExperience(title: String, company: Option[String], from: Option[String], to: Option[String], description: Option[String], isCurrent: Option[Boolean])
object JobExperience {
  implicit val format: Format[JobExperience] = Json.format
}

/**
  * Case class to wrap an Link/URL with a dervied provider/type
  *
  * @param url The actual URL/Link
  * @param provider Descripes the source/provider type of the Link e.g. Xing/Linkedin
  */
case class ProfileLink(url: String, provider: String)
object ProfileLink {
  implicit val format: Format[ProfileLink] = Json.format

  /**
    * Types
    */
  val PROVIDER_LINKED_IN = "linkedin"
  val PROVIDER_XING = "xing"

  /**
    * Derives type of an url
    * @param url Url to dervie type from
    * @return
    */
  def deriveProvider(url: String): String = {
    // regex pattern for Linkedin URL's
    val LinkedInPattern = ".*(linkedin).*/in/.*".r
    // regex pattern for Xing URL's
    val XingPattern = ".*(xing).*/profil/.*)".r

    //return correct type using pattern matching with the regex patterns defined above
    url match {
      case LinkedInPattern(m) => PROVIDER_LINKED_IN
      case XingPattern(m) => PROVIDER_XING
      case _ => throw ProfileScrapingException(s"Unable to derive Type from URL: $url")
    }
  }
}

trait ProfileScannerCommand

case class UpdateProfile(timestamp: Instant, profile: Profile) extends ProfileScannerCommand with ReplyType[Done]
object UpdateProfile {
  implicit val format: Format[UpdateProfile] = Json.format
}

case class ProfileScanFailure(url: String, timestamp: Instant, errorMsg: String)  extends ProfileScannerCommand with ReplyType[Done]
object ProfileScanFailure {
  implicit val format: Format[ProfileScanFailure] = Json.format
}

case class StartScan(timestamp: Instant, urls: Seq[String]) extends ProfileScannerCommand with ReplyType[Seq[String]]
object StartScan {
  implicit val format: Format[StartScan] = Json.format
}

case class FinishScan(timestamp: Instant, urls: Seq[String]) extends ProfileScannerCommand with ReplyType[Done]
object FinishScan {
  implicit val format: Format[FinishScan] = Json.format
}

case object GetProfiles extends ProfileScannerCommand with ReplyType[Profiles] {
  implicit val format: Format[GetProfiles.type] = singletonFormat(GetProfiles)
}

/**
  * Events using simple event tagging (a static tag for all events)
  *
  * In case of scaling issues this should be upgraded to sharded event tagging
  */
object ProfileScannerEvent {
  val Tag = AggregateEventTag[ProfileScannerEvent]
}
sealed trait ProfileScannerEvent extends AggregateEvent[ProfileScannerEvent] {
  override def aggregateTag: AggregateEventTag[ProfileScannerEvent] = ProfileScannerEvent.Tag
}

case class ProfileUpdated(timestamp: Instant, profile: Profile) extends ProfileScannerEvent
object ProfileUpdated {
  implicit val format: Format[ProfileUpdated] = Json.format
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
    JsonSerializer[Profile],
    JsonSerializer[JobExperience],
    JsonSerializer[StartScan],
    JsonSerializer[ScanStarted],
    JsonSerializer[ScanFinished],
    JsonSerializer[FinishScan],
    JsonSerializer[UpdateProfile],
    JsonSerializer[ProfileUpdated],
    JsonSerializer[ProfileScanFailed],
    JsonSerializer[ProfileScanFailure]
  )
}