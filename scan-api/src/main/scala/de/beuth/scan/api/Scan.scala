package de.beuth.scan.api

import de.beuth.censys.api.CensysIpv4Result
import play.api.libs.functional.syntax._
import play.api.libs.json.{Format, Json, Reads, Writes, _}

/**
  * Entity
  */

case class ScanRequest(keyword: String)

object ScanRequest { implicit val format: Format[ScanRequest] = Json.format[ScanRequest] }

case class ScanResult(timestamp: String, ipv4: Option[Seq[CensysIpv4Result]])

object ScanResult {
  implicit val format: Format[ScanResult] = Json.format
}

case class Ipv4Result(ip: String, protocols: Seq[String], location: Location, autonomous_system: AutonomousSystem, metadata: MetaData, tags: Option[Seq[String]], heartbleed: Option[HeartBleed], dns: Option[DNS])

object Ipv4Result {
  implicit val write: Writes[Ipv4Result] = Json.writes[Ipv4Result]
  implicit val reads: Reads[Ipv4Result] = (
    (__ \ "ip").read[String] and
      (__ \ "protocols").read[Seq[String]] and
      (__ \ "location").read[Location] and
      (__ \ "autonomous_system").read[AutonomousSystem] and
      (__ \ "metadata").read[MetaData] and
      (__ \ "tags").readNullable[Seq[String]] and
      (__ \\ "heartbleed").readNullable[HeartBleed] and
      (__ \\ "open_resolver").readNullable[DNS]
    )((ip, protocols, location, as, metadata, tags, heartbleed, dns) => Ipv4Result(ip, protocols, location, as, metadata, tags, heartbleed, dns))

}

case class Location(country: String, country_code: String, registered_country: String, city: String, longitude: Double, latitude: Double)

object Location {  implicit val format: Format[Location] = Json.format[Location]}

case class AutonomousSystem(description: String, name: String, organization: String)

object AutonomousSystem { implicit val format: Format[AutonomousSystem] = Json.format[AutonomousSystem]}

case class MetaData(description: Option[String], os: Option[String], os_version: Option[String], product: Option[String])

object MetaData { implicit val format: Format[MetaData] = Json.format[MetaData]}

case class HeartBleed(heartbeat_enabled: Boolean, heartbleed_vulnerable: Boolean)

object HeartBleed { implicit val format: Format[HeartBleed] = Json.format[HeartBleed]}

case class DNS(answers: String, authorities: String)

object DNS { implicit val format: Format[DNS] = Json.format[DNS]}

