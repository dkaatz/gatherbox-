package de.beuth.scan.api

import julienrf.json.derived
import org.joda.time.DateTime
import play.api.libs.json.{Format, __}

/**
  * Events
  */

sealed trait ScanEvent {
  val query: String
}

case class ScanStarted(query: String, timestamp: String) extends ScanEvent

case class ScanFinished(query: String, timestamp: String, result: ScanResult) extends ScanEvent

object ScanEvent {
  implicit val format: Format[ScanEvent] =
    derived.flat.oformat((__ \ "type").format[String])
}