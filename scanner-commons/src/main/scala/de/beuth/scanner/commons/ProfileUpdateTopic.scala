package de.beuth.scanner.commons

import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.broker.TopicProducer
import com.lightbend.lagom.scaladsl.persistence.{EventStreamElement, PersistentEntityRegistry}
import play.api.libs.json.{Format, Json}

trait ProfileUpdateTopic {
  def updateTopic(): Topic[ProfileUpdateEvent]

  def updateTopicImpl(registry: PersistentEntityRegistry): Topic[ProfileUpdateEvent] =
    TopicProducer.singleStreamWithOffset {
      fromOffset =>
        registry.eventStream(ScannerEvent.Tag , fromOffset)
          .filter {
            _.event match {
              //@todo add scanFailed
              case ev: ProfileUpdated if ev.profile.firstname.isDefined && ev.profile.lastname.isDefined => true
              case _ => false
            }
          }.map(ev => (convertScannedEvent(ev.entityId, ev), ev.offset))
    }

  private def convertScannedEvent(keyword: String, scanEvent: EventStreamElement[ScannerEvent]): ProfileUpdateEvent = {
    scanEvent.event match {
      case ProfileUpdated(timestamp, profile) => ProfileUpdateEvent(keyword, profile)
    }
  }
}

case class ProfileUpdateEvent(keyword: String, profile: Profile)
object ProfileUpdateEvent {
  implicit val format: Format[ProfileUpdateEvent] = Json.format
}