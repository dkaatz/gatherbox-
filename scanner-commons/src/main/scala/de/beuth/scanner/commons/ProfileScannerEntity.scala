package de.beuth.scanner.commons

import java.time.Instant
import java.time.temporal.ChronoUnit

import akka.Done
import com.lightbend.lagom.scaladsl.persistence.PersistentEntity.ReplyType
import com.lightbend.lagom.scaladsl.playjson.{JsonSerializer, JsonSerializerRegistry}
import play.api.libs.json.{Format, Json}
import de.beuth.utils.JsonFormats.singletonFormat

import scala.collection.immutable.Seq

trait ProfileScannerEntity extends ScannerEntity {

  override def initialState: ProfileScannerState = ProfileScannerState(None, false, false, Seq())

  def profileBehavior: Actions = Actions()
    /**
      * Adding links to scan
      */
    .onCommand[AddLinks, Seq[String]] {
    case (AddLinks(timestamp, urls), ctx, state: ProfileScannerState) =>
      ctx.thenPersist(
        LinksAdded(timestamp, urls)
      ) {
        _ => ctx.reply(urls)
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
      * We scanned a profile
      */
    .onCommand[ScannedProfile, Done] {
      case (ScannedProfile(timestamp, profile), ctx, state: ProfileScannerState)
        if isLastProfile(profile, state) =>
        ctx.thenPersistAll(
          ProfileUpdated(timestamp, profile),
          ProfileScanned(profile),
          ScanFinished(timestamp)
        ) {
          case _ => ctx.reply(Done)
        }
      //usal profile update
      case (ScannedProfile(timestamp, profile), ctx, state: ProfileScannerState) =>
        ctx.thenPersistAll(
          ProfileUpdated(timestamp, profile),
          ProfileScanned(profile)
        ) {
          case _ => ctx.reply(Done)
        }
    }
    /**
      * We Update the profile from a existing read side processor entry
      */
    .onCommand[UpdateProfile, Done] {
    case (UpdateProfile(timestamp, profile), ctx, state: ProfileScannerState)
      //if we have collected all links and the updated profile is the last not scanned profile we have finished the scan
      if isLastProfile(profile, state) =>

      ctx.thenPersistAll(
        ProfileUpdated(timestamp, profile),
        ScanFinished(timestamp)
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
    case (ProfileUpdated(timestamp, profile), state: ProfileScannerState) => state.updateProfile(timestamp, profile)
    case (LinksAdded(timestamp, urls), state: ProfileScannerState) => state.addLinks(timestamp, urls)
    case (LinkCollectionCompleted(), state: ProfileScannerState) => state.completeCollection
    case (ProfileScanned(profile), state: ProfileScannerState) => state
    case (ProfileScanFailed(url, timestmap, msg), state: ProfileScannerState) => state.removeProfile(url)
  }

  private def isLastProfile(profile: Profile, state: ProfileScannerState): Boolean =
    state.collected && state.profiles.filterNot(_.scanned).length == 1 && state.profiles.filterNot(_.scanned).exists(profile.url == _.url)

  protected val getProfiles = Actions().onReadOnlyCommand[GetProfiles.type, ProfileScannerState] { case (GetProfiles, ctx, state: ProfileScannerState) => ctx.reply(state) }
}

case class ProfileScannerState(
                        startedat: Option[Instant],
                        finished: Boolean,
                        collected: Boolean,
                        profiles: Seq[Profile]) extends ScannerState {


  def start(timestamp: Instant): ProfileScannerState = copy(startedat=Some(timestamp), finished = false, collected = false)

  def finish: ProfileScannerState = copy(finished = true)

  def completeCollection: ProfileScannerState = copy(collected = true)

  /**
    * Adds a sequence of links to the collection of profile links and filters out already existing urls before
    *
    * @param timestamp time when event occured
    * @param urls sequence of urls to add to the collection
    * @return
    */
  def addLinks(timestamp: Instant, urls: Seq[String]): ProfileScannerState = {
    val oldProfiles = profiles.filterNot(profile => urls.exists(url => profile.url == url))
    copy(
      // join the sequence of profiles that are not rescheduled for scan and the ones that are sheduled for a scan
      profiles = oldProfiles ++ urls.map(url => Profile(
        scanned = false,
        firstname = None,
        lastname = None,
        skills = Seq(),
        exp = Seq(),
        updatedat = timestamp,
        url = url
      ))
    )
   }

  /**
    * Overrides the given Profile
    *
    * @param timestamp time the event got sent
    * @param profile profile to update
    * @return the updated entity
    */
  def updateProfile(timestamp: Instant, profile: Profile): ProfileScannerState = {
    //@Outcomment this if you want to test Profile Scanner Only
    val pIdx = profiles.indexWhere(_.url == profile.url)
    if(pIdx == -1) throw ProfileScrapingException("Trying to update uninitialized Profile: " + Json.toJson(profile).toString())
    copy(
      profiles = profiles.updated(pIdx, profile)
    )
    //@Incomment this if you want to test Profile Scanner Only
    //copy(profiles = profiles :+ profile)
  }

  def removeProfile(url: String): ProfileScannerState =
    copy(
      profiles = profiles.filterNot(_.url == url)
    )

}

/**
  * Represents the extracted Profile Data
  *
  * @param firstname  givenname of the person
  * @param lastname surname of the person
  * @param updatedat  date when the profile got updated by any scan event the last time
  * @param url url to the profile
  * @param skills list of skills extracted from the profile
  * @param exp list of jobs extracted from the profile
  */
case class Profile(scanned: Boolean,
                   firstname: Option[String],
                   lastname: Option[String],
                   updatedat: Instant,
                   url: String,
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
  * @param started string representing the date when the user started to work at this job
  * @param ended string representing the date when the user ended to work at this job
  * @param description describtion of the job
  * @param isCurrent describes if this is the current job
  */
case class JobExperience(title: String, company: Option[String], started: Option[String], ended: Option[String], description: Option[String], isCurrent: Option[Boolean])
object JobExperience {
  implicit val format: Format[JobExperience] = Json.format
}


/**
  * Commands
  */
case class AddLinks(timestamp: Instant, urls: Seq[String]) extends ScannerCommand with ReplyType[Seq[String]]
object AddLinks {
  implicit val format: Format[AddLinks] = Json.format
}

case class CompleteLinkCollection() extends ScannerCommand with ReplyType[Done]
object CompleteLinkCollection {
  implicit val format: Format[CompleteLinkCollection.type] = singletonFormat(CompleteLinkCollection)
}

case class UpdateProfile(timestamp: Instant, profile: Profile) extends ScannerCommand with ReplyType[Done]
object UpdateProfile {
  implicit val format: Format[UpdateProfile] = Json.format
}

case class ProfileScanFailure(url: String, timestamp: Instant, errorMsg: String)  extends ScannerCommand with ReplyType[Done]
object ProfileScanFailure {
  implicit val format: Format[ProfileScanFailure] = Json.format
}

case class ScannedProfile(timestamp: Instant, profile: Profile)  extends ScannerCommand with ReplyType[Done]
object ScannedProfile {
  implicit val format: Format[ScannedProfile] = Json.format
}

case object GetProfiles extends ScannerCommand with ReplyType[ProfileScannerState] {
  implicit val format: Format[GetProfiles.type] = singletonFormat(GetProfiles)
}


/**
  * Events
  */
case class LinkCollectionCompleted() extends ScannerUpdateEvent
object LinkCollectionCompleted {
  implicit val format: Format[LinkCollectionCompleted.type] = singletonFormat(LinkCollectionCompleted)
}

case class LinksAdded(timestamp: Instant, urls: Seq[String]) extends ScannerUpdateEvent
object LinksAdded {
  implicit val format: Format[LinksAdded] = Json.format
}

case class ProfileUpdated(timestamp: Instant, profile: Profile) extends ScannerUpdateEvent
object ProfileUpdated {
  implicit val format: Format[ProfileUpdated] = Json.format
}

case class ProfileScanned(profile: Profile)  extends ScannerUpdateEvent
object ProfileScanned {
  implicit val format: Format[ProfileScanned] = Json.format
}

case class ProfileScanFailed(url: String, timestamp: Instant, errorMsg: String) extends ScannerUpdateEvent
object ProfileScanFailed {
  implicit val format: Format[ProfileScanFailed] = Json.format
}

case class ProfileScrapingException(message: String) extends Exception(message)


object ProfileScanSerializerRegistry extends JsonSerializerRegistry {
  override def serializers: Seq[JsonSerializer[_]] = Seq(
    JsonSerializer[Profile],
    JsonSerializer[JobExperience],
    JsonSerializer[LinkCollectionCompleted.type],
    JsonSerializer[CompleteLinkCollection.type],
    JsonSerializer[AddLinks],
    JsonSerializer[LinksAdded],
    JsonSerializer[UpdateProfile],
    JsonSerializer[ProfileUpdated],
    JsonSerializer[ProfileScanFailed],
    JsonSerializer[ProfileScanFailure],
    JsonSerializer[ScannedProfile],
    JsonSerializer[ProfileScanned]
  ) ++ ScannerSerialzierRegistry.serializers
}