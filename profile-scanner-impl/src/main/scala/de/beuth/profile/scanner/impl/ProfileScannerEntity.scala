package de.beuth.profile.scanner.impl

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.{AggregateEvent, AggregateEventTag, PersistentEntity}
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.ReplyType
import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}
import play.api.libs.json._
import de.beuth.utils.JsonFormats.singletonFormat
import de.beuth.utils.ProfileLink

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
  override def initialState: Profiles = Profiles(Instant.now(), false, Seq())

  override def behavior: Behavior = {
    case scan => Actions()

      /**
        * On start scan we just set collected to false and update the last update time
        */
      .onCommand[StartScan, Done] {
        case (StartScan(timestamp), ctx, state) =>
          ctx.thenPersist(
            ScanStarted(timestamp)
          ) {
            _ => ctx.reply(Done)
          }
    }

    /**
      * Adding links to scan
      */
    .onCommand[AddLinks, Seq[String]] {
      case (AddLinks(timestamp, urls), ctx, state) =>
        //getting all already present profiles that are not out dated
        val notOutDated = state.profiles.filter(profile => timestamp.minus(12, ChronoUnit.HOURS).isBefore(profile.updatedAt))

        val linksToScan =
          //filter out all profile urls that do exist in the list of profiles that are not outdated yet
          urls.filterNot(url => notOutDated.exists(profile => profile.link.url == url)) ++
          //getting al newly added urls
          urls.filterNot(url => state.profiles.exists(profile => profile.link.url == url))

        ctx.thenPersist(
          LinksAdded(timestamp, linksToScan)
        ) {
          _ => ctx.reply(linksToScan)
        }
    }
    /**
      * Link collection is completed
      */
    .onCommand[CompleteLinkCollection, Done ] {
      case (CompleteLinkCollection(), ctx, state) =>
        ctx.thenPersist(LinkCollectionCompleted()) {
          _ => ctx.reply(Done)
        }
    }
    /**
      * On UpdateProfile persist the event and reply with done
      */
    .onCommand[UpdateProfile, Done] {
      case (UpdateProfile(timestamp, profile), ctx, state)
        //if we have collected all links and the updated profile is the last not scanned profile we have finished the scan
        if state.collected
            && state.profiles.filterNot(_.scanned).length == 1
            && state.profiles.filterNot(_.scanned).exists(profile.link.url == _.link.url) =>
        ctx.thenPersistAll(
          ProfileUpdated(timestamp, profile),
          ScanFinished()
        ) {
          case _ => ctx.reply(Done)
        }
      //usal profile update
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
      case (ScanStarted(timestamp), state) => state.startScan(timestamp)
      case (ProfileUpdated(timestamp, profile), state) => state.updateProfile(timestamp, profile)
      case (LinksAdded(timestamp, urls), state) => state.addLinks(timestamp, urls)
      case (LinkCollectionCompleted(), state) => state.completeCollection()
    /**
      *  events that do not change the state but are persisted for communication integrity
      */
      case (ScanFinished(), state) => state
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
  * @param collected describes if all links for the keyword are collected
  * @param profiles list of profiles (a profile may contains just the link)
  */
case class Profiles(updatedAt: Instant, collected: Boolean, profiles: Seq[Profile]) {

  /**
    * Initializes a new scan by setting collected to false
    *
    * @param timestamp time when event occured
    * @return updated entity
    */
  def startScan(timestamp: Instant): Profiles = {
    copy(updatedAt = timestamp, collected = false)
  }

  /**
    * Set collected to true to mark collection of profile links as finished
    * @return updated entity
    */
  def completeCollection(): Profiles = {
    copy(collected = true)
  }

  /**
    * Adds a sequence of links to the collection of profile links and filters out already existing urls before
    *
    * @param timestamp time when event occured
    * @param urls sequence of urls to add to the collection
    * @return
    */
  def addLinks(timestamp: Instant, urls: Seq[String]): Profiles = {
    //filter out all profiles that are not getting scanned
    val oldProfiles = profiles.filterNot(profile => urls.exists(url => profile.link.url == url))
    copy(
      updatedAt = timestamp,
      // join the sequence of profiles that are not rescheduled for scan and the ones that are sheduled for a scan
      profiles = oldProfiles ++ urls.map(url => Profile(
        scanned = false,
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
case class Profile(scanned: Boolean,
                   firstname: Option[String],
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

trait ProfileScannerCommand

case class AddLinks(timestamp: Instant, urls: Seq[String]) extends ProfileScannerCommand with ReplyType[Seq[String]]
object AddLinks {
  implicit val format: Format[AddLinks] = Json.format
}

case class CompleteLinkCollection() extends ProfileScannerCommand with ReplyType[Done]
object CompleteLinkCollection {
  implicit val format: Format[CompleteLinkCollection.type] = singletonFormat(CompleteLinkCollection)
}

case class UpdateProfile(timestamp: Instant, profile: Profile) extends ProfileScannerCommand with ReplyType[Done]
object UpdateProfile {
  implicit val format: Format[UpdateProfile] = Json.format
}

case class ProfileScanFailure(url: String, timestamp: Instant, errorMsg: String)  extends ProfileScannerCommand with ReplyType[Done]
object ProfileScanFailure {
  implicit val format: Format[ProfileScanFailure] = Json.format
}

case class StartScan(timestamp: Instant) extends ProfileScannerCommand with ReplyType[Done]
object StartScan {
  implicit val format: Format[StartScan] = Json.format
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

case class LinkCollectionCompleted() extends ProfileScannerEvent
object LinkCollectionCompleted {
  implicit val format: Format[LinkCollectionCompleted.type] = singletonFormat(LinkCollectionCompleted)
}

case class LinksAdded(timestamp: Instant, urls: Seq[String]) extends ProfileScannerEvent
object LinksAdded {
  implicit val format: Format[LinksAdded] = Json.format
}

case class ProfileUpdated(timestamp: Instant, profile: Profile) extends ProfileScannerEvent
object ProfileUpdated {
  implicit val format: Format[ProfileUpdated] = Json.format
}

case class ProfileScanFailed(url: String, timestamp: Instant, errorMsg: String) extends ProfileScannerEvent
object ProfileScanFailed {
  implicit val format: Format[ProfileScanFailed] = Json.format
}

case class ScanStarted(timestamp: Instant) extends ProfileScannerEvent
object ScanStarted {
  implicit val format: Format[ScanStarted] = Json.format
}

case class ScanFinished() extends ProfileScannerEvent
object ScanFinished {
  implicit val format: Format[ScanFinished.type] = singletonFormat(ScanFinished)
}

object ProfileScanSerializerRegistry extends JsonSerializerRegistry {
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[Profile],
    JsonSerializer[JobExperience],
    JsonSerializer[LinkCollectionCompleted.type],
    JsonSerializer[CompleteLinkCollection.type],
    JsonSerializer[AddLinks],
    JsonSerializer[LinksAdded],
    JsonSerializer[StartScan],
    JsonSerializer[ScanStarted],
    JsonSerializer[ScanFinished.type],
    JsonSerializer[UpdateProfile],
    JsonSerializer[ProfileUpdated],
    JsonSerializer[ProfileScanFailed],
    JsonSerializer[ProfileScanFailure]
  )
}