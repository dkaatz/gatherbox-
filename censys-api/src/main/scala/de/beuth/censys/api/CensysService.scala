package de.beuth.censys.api

import com.lightbend.lagom.scaladsl.api.security.ServicePrincipal
import com.lightbend.lagom.scaladsl.api.transport.{HeaderFilter, RequestHeader, ResponseHeader}
import com.lightbend.lagom.scaladsl.api.{Service, ServiceCall}
import play.api.http.HeaderNames
import play.api.libs.json.{Format, Json, Reads, Writes, _}
import play.api.libs.functional.syntax._
import java.util.Base64
import java.nio.charset.StandardCharsets
import scala.collection.immutable.Seq


trait CensysService extends Service {

  def searchIpv4(): ServiceCall[CensysQuery, CensysIpv4SearchResult]

  override final def descriptor = {
    import Service._
    import com.lightbend.lagom.scaladsl.api.transport.Method
    named("censys").withCalls(
      restCall(Method.POST,   "/api/v1/search/ipv4", searchIpv4)
    ).withHeaderFilter(CensysHeaderFilter)
  }
}

object CensysHeaderFilter extends HeaderFilter {

  //@todo get api key from config
  override def transformClientRequest(request: RequestHeader) = {
    request.principal match {
      case Some(principal: ServicePrincipal) =>
        request.withHeader(
          HeaderNames.AUTHORIZATION,
          "Basic " + Base64.getEncoder.encodeToString("6e085b60-b028-48de-bc1f-13857f1ed033:PkzJkBzcJBBjIkySRVfBO7e8ULxJC3wO".getBytes(StandardCharsets.UTF_8)))
      case _ => request
    }
  }

  override def transformServerRequest(request: RequestHeader) = request

  override def transformServerResponse(
                                        response: ResponseHeader,
                                        request:  RequestHeader
                                      ) = response

  override def transformClientResponse(
                                        response: ResponseHeader,
                                        request:  RequestHeader
                                      ) = response
}

case class CensysQuery(query: String, fields: Seq[String] = Seq("ip", "protocols", "location", "autonomous_system", "metadata", "tags", "443.https.headtbleed", "53.dns.open_resolver"),
                       page: Int = 1, flatten: Boolean = false)

object CensysQuery { implicit val format: Format[CensysQuery] = Json.format[CensysQuery] }

case class CensysIpv4SearchResult(status: String, metadata: CensysMetaData, results: Seq[CensysIpv4Result])

object CensysIpv4SearchResult { implicit val format: Format[CensysIpv4SearchResult] = Json.format[CensysIpv4SearchResult] }

case class CensysMetaData(count: Int, query: String, backend_time: Int, page: Int, pages: Int)

object CensysMetaData { implicit val format: Format[CensysMetaData] = Json.format[CensysMetaData] }

case class CensysIpv4Result(ip: String, protocols: Seq[String], location: Location, autonomous_system: Option[AutonomousSystem], metadata: MetaData, tags: Option[Seq[String]], heartbleed: Option[HeartBleed], dns: Option[DNS])

object CensysIpv4Result {
  implicit val write: Writes[CensysIpv4Result] = Json.writes[CensysIpv4Result]
  implicit val reads: Reads[CensysIpv4Result] = (
      (__ \ "ip").read[String] and
      (__ \ "protocols").read[Seq[String]] and
      (__ \ "location").read[Location] and
      (__ \ "autonomous_system").readNullable[AutonomousSystem] and
      (__ \ "metadata").read[MetaData] and
      (__ \ "tags").readNullable[Seq[String]] and
      (__ \\ "heartbleed").readNullable[HeartBleed] and
      (__ \\ "open_resolver").readNullable[DNS]
    )((ip, protocols, location, as, metadata, tags, heartbleed, dns) => CensysIpv4Result(ip, protocols, location, as, metadata, tags, heartbleed, dns))

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