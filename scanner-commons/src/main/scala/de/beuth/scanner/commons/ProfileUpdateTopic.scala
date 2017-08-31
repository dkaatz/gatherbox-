package de.beuth.scanner.commons

import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.broker.TopicProducer
import com.lightbend.lagom.scaladsl.persistence.{EventStreamElement, PersistentEntityRegistry}
import play.api.libs.json.{Format, Json}

/**
  * Supertype for profile update topics
  */
trait ProfileUpdateTopic {
  def updateTopic(): Topic[ProfileUpdateEvent]

  def updateTopicImpl(registry: PersistentEntityRegistry): Topic[ProfileUpdateEvent] =
    TopicProducer.singleStreamWithOffset {
      fromOffset =>
        registry.eventStream(ScannerEvent.Tag , fromOffset)
          .filter {
            _.event match {
                //we just want to publish the event if the firstname nad lastname is defined
              case ev: ProfileUpdated if ev.profile.firstname.isDefined && ev.profile.lastname.isDefined => true
              case _ => false
            }
          }.map(ev => (convertScannedEvent(ev.entityId, ev), ev.offset))
    }

  //@todo this method is not required we can remove the match and just emit the event
  private def convertScannedEvent(keyword: String, scanEvent: EventStreamElement[ScannerEvent]): ProfileUpdateEvent = {
    scanEvent.event match {
      case ProfileUpdated(timestamp, profile) => ProfileUpdateEvent(keyword, profile)
    }
  }
}

/**
  * Message type for the profile update topic
  * @param keyword keyword the profile belongs to
  * @param profile profile to publish
  */
case class ProfileUpdateEvent(keyword: String, profile: Profile)
object ProfileUpdateEvent {
  implicit val format: Format[ProfileUpdateEvent] = Json.format
}