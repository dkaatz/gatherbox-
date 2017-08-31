package de.beuth.scan.results.api

import java.time.Instant

import akka.{Done, NotUsed}
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
import de.beuth.censys.api._
import de.beuth.databreach.scanner.api.DataBreachResult
import de.beuth.scanner.commons.{JobExperience, Profile}
import play.api.libs.json.{Format, Json}

import scala.collection.immutable.Seq

/**
  * This service collects and provides the results of a scan and just has one endpoint and no topics
  */
trait ScanResultService extends Service{

  def getResults(keyword: String): ServiceCall[NotUsed, ScanResult]

  override final def descriptor = {
    import Service._
    //name of the scanner
    named("scanresult").withCalls(
      //endpoint to ask for results
      pathCall("/api/scan/:keyword/results", getResults _)
    ).withAutoAcl(true)
  }
}

/**
  * The scan result object, defines the return format for a endpoint call
  *
  * @param keyword the scanned keyword
  * @param censys the censys results
  * @param linkedin the linkedin results + data breach results
  * @param xing the xing results + data breach results
  */
case class ScanResult(keyword: String, censys: Seq[CensysResult], linkedin: Seq[ProfileResult], xing: Seq[ProfileResult])
object ScanResult {
  implicit val format: Format[ScanResult] = Json.format
}

/**
  * Wrapper for Censys results format, just adding the last update
  */
case class CensysResult(updatedat: Instant,
                        ip: String,
                        protocols: Seq[String],
                        location: Location,
                        autonomous_system: Option[AutonomousSystem],
                        metadata: MetaData,
                        tags: Option[Seq[String]],
                        heartbleed: Option[HeartBleed],
                        dns: Option[DNS])
object CensysResult {
  implicit val format: Format[CensysResult] = Json.format

  //overloaded constructor for creating the CensysResult out of a CensysIpv4Result
  def apply(censys: CensysIpv4Result): CensysResult = CensysResult(
    Instant.now(),
    censys.ip,
    censys.protocols,
    censys.location,
    censys.autonomous_system,
    censys.metadata,
    censys.tags,
    censys.heartbleed,
    censys.dns)
}

/**
  * Profile result containing the profile informations and the databreach informations and the last update
  */
case class ProfileResult(updatedat: Instant,
                         url: String,
                         firstname: String,
                         lastname: String,
                         skills: Seq[String],
                         exp: Seq[JobExperience],
                         dataBreach: Seq[DataBreachResult])
object ProfileResult {
  implicit val format: Format[ProfileResult] = Json.format

  //overloaded constructor for creating the ProfileResult out of a Profile
  def apply(profile: Profile): ProfileResult = ProfileResult(
    Instant.now(),
    profile.url,
    profile.firstname.getOrElse(""),
    profile.lastname.getOrElse(""),
    profile.skills,
    profile.exp,
    Seq())
}