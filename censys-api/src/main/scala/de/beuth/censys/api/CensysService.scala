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

/**
  * This Services provides the interface for the external API https://www.censy.io
  *
  * It contains one API endpoint wich has the same route as the api endpoint at censys.io
  */
trait CensysService extends Service {

  def searchIpv4(): ServiceCall[CensysQuery, CensysIpv4SearchResult]

  override final def descriptor = {
    import Service._
    import com.lightbend.lagom.scaladsl.api.transport.Method
    //name of the service
    named("censys").withCalls(
      //endpoint description
      restCall(Method.POST,   "/api/v1/search/ipv4", searchIpv4)
    )
      //added to provide authentication header to censys
      .withHeaderFilter(CensysHeaderFilter)
  }
}

/**
  * Helper that adds the authorization header to the request
  */
object CensysHeaderFilter extends HeaderFilter {
  override def transformClientRequest(request: RequestHeader) = {
    request.principal match {
      case Some(principal: ServicePrincipal) =>
        request.withHeader(
          //header name
          HeaderNames.AUTHORIZATION,
          //header value  -- unfortunately i found no way to extract the api token from the configuration
          "Basic " + Base64.getEncoder.encodeToString("6e085b60-b028-48de-bc1f-13857f1ed033:PkzJkBzcJBBjIkySRVfBO7e8ULxJC3wO".getBytes(StandardCharsets.UTF_8)))
      case _ => request
    }
  }

  //not used we just return the request
  override def transformServerRequest(request: RequestHeader) = request

  //not used we just return the request
  override def transformServerResponse(
                                        response: ResponseHeader,
                                        request:  RequestHeader
                                      ) = response
  //not used we just return the request
  override def transformClientResponse(
                                        response: ResponseHeader,
                                        request:  RequestHeader
                                      ) = response
}

/**
  * Censys query wich specifies the query that is sent to censys
  * @param query  the query string itself
  * @param fields  fields in the response
  * @param page  page to scrape for pagination
  * @param flatten  if values should get flatten or not (just works with not flatten values)
  */
case class CensysQuery(query: String, fields: Seq[String] = Seq("ip", "protocols", "location", "autonomous_system", "metadata", "tags", "443.https.headtbleed", "53.dns.open_resolver"),
                       page: Int = 1, flatten: Boolean = false)

//companion object providing serialization an deserialization
object CensysQuery { implicit val format: Format[CensysQuery] = Json.format[CensysQuery] }

/**
  * Search results from censys search
  *
  * @param status the http status of the response
  * @param metadata the meta data object of the response
  * @param results the results of the response
  */
case class CensysIpv4SearchResult(status: String, metadata: CensysMetaData, results: Seq[CensysIpv4Result])


//companion object providing serialization an deserialization
object CensysIpv4SearchResult { implicit val format: Format[CensysIpv4SearchResult] = Json.format[CensysIpv4SearchResult] }

/**
  * Meta data of a query response
  *
  * @param count result count
  * @param query query that got performed
  * @param backend_time the time the censy backend took to respond
  * @param page  the returned page
  * @param pages the page count
  */
case class CensysMetaData(count: Int, query: String, backend_time: Int, page: Int, pages: Int)

//companion object providing serialization an deserialization
object CensysMetaData { implicit val format: Format[CensysMetaData] = Json.format[CensysMetaData] }

/**
  * A result of a query
  *
  * @param ip ip adress of server
  * @param protocols open ports/protocols
  * @param location location object
  * @param autonomous_system as object
  * @param metadata the meta data of the server e.g.  operation system
  * @param tags tags that this server includes
  * @param heartbleed informations about heartbleed vulnerablility
  * @param dns informations about dns awsners
  */
case class CensysIpv4Result(ip: String, protocols: Seq[String], location: Location, autonomous_system: Option[AutonomousSystem], metadata: MetaData, tags: Option[Seq[String]], heartbleed: Option[HeartBleed], dns: Option[DNS])

//companion object providing serialization an deserialization
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

/**
  * Location object
  *
  * @param country
  * @param country_code
  * @param registered_country
  * @param city
  * @param longitude
  * @param latitude
  */
case class Location(country: String, country_code: String, registered_country: String, city: String, longitude: Double, latitude: Double)
//companion object providing serialization an deserialization
object Location {  implicit val format: Format[Location] = Json.format[Location]}

/**
  * Data of the AS the server is running in
  * @param description
  * @param name
  * @param organization
  */
case class AutonomousSystem(description: String, name: String, organization: String)
//companion object providing serialization an deserialization
object AutonomousSystem { implicit val format: Format[AutonomousSystem] = Json.format[AutonomousSystem]}

/**
  * Meta data of the server
  *
  * @param description
  * @param os
  * @param os_version
  * @param product
  */
case class MetaData(description: Option[String], os: Option[String], os_version: Option[String], product: Option[String])

//companion object providing serialization an deserialization
object MetaData { implicit val format: Format[MetaData] = Json.format[MetaData]}

/**
  * Heartbleed informations
  *
  * @param heartbeat_enabled  is heartbleed enabled on the server?
  * @param heartbleed_vulnerable is the server vulnerable?
  */
case class HeartBleed(heartbeat_enabled: Boolean, heartbleed_vulnerable: Boolean)

//companion object providing serialization an deserialization
object HeartBleed { implicit val format: Format[HeartBleed] = Json.format[HeartBleed]}

/**
  * DNS
  * @param answers comma seperated answers
  * @param authorities comma seperated authorities
  */
case class DNS(answers: String, authorities: String)
//companion object providing serialization an deserialization
object DNS { implicit val format: Format[DNS] = Json.format[DNS]}
